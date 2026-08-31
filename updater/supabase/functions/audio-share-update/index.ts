type UpdateRow = {
    channel: string;
    latest_version: string;
    zip_url: string;
    signature: string;
    notes: string;
    published_at: string;
    enabled: boolean;
};

const JSON_HEADERS = {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
};

const CHANNELS: Record<string, string> = {
    'windows:x86_64': 'audio-share-server-stable',
    'android:universal': 'audio-share-android-stable',
};

function jsonResponse(body: unknown, status: number): Response {
    return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

function noUpdateResponse(): Response {
    return new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store' } });
}

function parseVersion(value: string): [number, number, number] | null {
    const match = value.trim().match(/^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$/);
    if (!match) return null;
    return [Number(match[1]), Number(match[2]), Number(match[3])];
}

function compareVersions(left: [number, number, number], right: [number, number, number]): number {
    for (let index = 0; index < left.length; index += 1) {
        if (left[index] !== right[index]) return left[index] - right[index];
    }
    return 0;
}

function normalizeDownloadUrl(value: string): string {
    const url = new URL(value);
    if (url.protocol !== 'https:') throw new Error('Update artifact URL must use HTTPS.');

    const hostname = url.hostname.toLowerCase();
    if (hostname !== 'dropbox.com' && !hostname.endsWith('.dropbox.com')) {
        throw new Error('Update artifact URL must use Dropbox.');
    }
    url.searchParams.delete('raw');
    url.searchParams.set('dl', '1');
    return url.toString();
}

async function readUpdateRow(channel: string): Promise<UpdateRow | null> {
    const supabaseUrl = Deno.env.get('SUPABASE_URL');
    const namedSecretKeys = Deno.env.get('SUPABASE_SECRET_KEYS');
    let defaultSecretKey = '';
    if (namedSecretKeys) {
        const parsedKeys = JSON.parse(namedSecretKeys) as Record<string, string>;
        defaultSecretKey = parsedKeys.default ?? '';
    }
    const secretKey = Deno.env.get('SUPABASE_SECRET_KEY') || defaultSecretKey ||
        Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
    if (!supabaseUrl || !secretKey) {
        throw new Error('Supabase server environment is not configured.');
    }

    const requestUrl = new URL('/rest/v1/audio_share_updates', supabaseUrl);
    requestUrl.searchParams.set(
        'select',
        'channel,latest_version,zip_url,signature,notes,published_at,enabled',
    );
    requestUrl.searchParams.set('channel', `eq.${channel}`);
    requestUrl.searchParams.set('limit', '1');

    const headers: Record<string, string> = { apikey: secretKey };
    if (!secretKey.startsWith('sb_')) headers.Authorization = `Bearer ${secretKey}`;

    const response = await fetch(requestUrl, { headers });
    if (!response.ok) {
        const details = (await response.text()).slice(0, 500);
        throw new Error(`Supabase query failed with HTTP ${response.status}: ${details}`);
    }
    const rows = (await response.json()) as UpdateRow[];
    return rows[0] ?? null;
}

Deno.serve(async (request: Request) => {
    if (request.method !== 'GET') return jsonResponse({ error: 'Method not allowed.' }, 405);

    try {
        const requestUrl = new URL(request.url);
        const channel = requestUrl.searchParams.get('channel')?.trim() || '';
        const target = requestUrl.searchParams.get('target')?.trim() || '';
        const arch = requestUrl.searchParams.get('arch')?.trim() || '';
        const currentVersionText = requestUrl.searchParams.get('current_version')?.trim() || '';
        const expectedChannel = CHANNELS[`${target}:${arch}`];

        if (!expectedChannel || channel !== expectedChannel) return noUpdateResponse();
        const currentVersion = parseVersion(currentVersionText);
        if (!currentVersion) return jsonResponse({ error: 'Invalid current_version.' }, 400);

        const row = await readUpdateRow(channel);
        if (!row || !row.enabled) return noUpdateResponse();

        const latestVersion = parseVersion(row.latest_version);
        if (!latestVersion) {
            return jsonResponse({ error: 'The configured latest_version is not valid SemVer.' }, 500);
        }
        if (compareVersions(latestVersion, currentVersion) <= 0) return noUpdateResponse();
        if (!row.signature.trim()) {
            return jsonResponse({ error: 'The update signature is missing.' }, 500);
        }

        return jsonResponse(
            {
                version: row.latest_version,
                pub_date: row.published_at,
                url: normalizeDownloadUrl(row.zip_url),
                signature: row.signature.trim(),
                notes: row.notes,
            },
            200,
        );
    } catch (error) {
        const message = error instanceof Error ? error.message : 'Unknown updater error.';
        return jsonResponse({ error: message }, 500);
    }
});
