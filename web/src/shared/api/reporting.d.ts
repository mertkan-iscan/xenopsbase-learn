export interface paths {
    "/api/v1/internal/whoami": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["whoami"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/telemetry/playback": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["playback"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        AcceptedView: {
            /** Format: int32 */
            samples?: number;
        };
        HeartbeatBatch: {
            /** Format: uuid */
            nodeId?: string;
            playbackToken?: string;
            samples?: components["schemas"]["PlaybackSample"][];
        };
        PlaybackSample: {
            /** Format: int32 */
            fromSecond?: number;
            /** Format: date-time */
            observedAt?: string;
            /** Format: double */
            rate?: number;
            /** Format: int32 */
            toSecond?: number;
        };
        /**
         * @description An RFC 9457 problem document. Every refusal this platform writes has this shape, on `application/problem+json`.
         *
         *     RFC 9457 permits extension members and this platform uses one, `code`. Switch on that rather than on `type`: a URI invites prefix-matching and string surgery, and the short token is the thing that stays readable in a client.
         */
        Problem: {
            /** @description The extension member a client switches on, e.g. `PLAYBACK_NOT_ENTITLED`. Absent when the refusal has no machine-readable identity behind it, such as a bare `ResponseStatusException`. */
            code?: string;
            /** @description What went wrong THIS time, written for whoever is on the other end. Absent when the refusal must not describe itself. */
            detail?: string;
            /**
             * Format: uri
             * @description The occurrence this document is about, when there is one.
             */
            instance?: string;
            /**
             * Format: int32
             * @description The HTTP status code, repeated in the document.
             */
            status?: number;
            /** @description A short, human-readable summary of the kind of problem. */
            title?: string;
            /**
             * Format: uri
             * @description A stable URI identifying the kind of problem. It does not resolve; RFC 9457 says it need not.
             */
            type?: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    whoami: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: unknown;
                    };
                };
            };
            /**
             * @description The account was refused before the handler ran: the company is suspended, or it is read-only and this is a write. Every path under `/api` answers this.
             *
             *     A permission denial can also answer 403, and it carries **no body** — a refusal that described itself would confirm the resource exists, which is what the disclosure rule is protecting.
             */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    playback: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["HeartbeatBatch"];
            };
        };
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AcceptedView"];
                };
            };
            /** @description The batch will never be accepted, so do not resend it. `code` says which rule it broke: `EMPTY_BATCH`, `MALFORMED_INTERVAL`, `IMPLAUSIBLE_RATE`, `MISSING_ATTRIBUTION`, or `MALFORMED_BATCH` when the JSON itself would not parse. */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /**
             * @description The account was refused before the handler ran: the company is suspended, or it is read-only and this is a write. Every path under `/api` answers this.
             *
             *     A permission denial can also answer 403, and it carries **no body** — a refusal that described itself would confirm the resource exists, which is what the disclosure rule is protecting.
             */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description `BATCH_TOO_LARGE`. Split it and post the halves; nothing was recorded. */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
}
