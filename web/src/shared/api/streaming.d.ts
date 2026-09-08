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
    "/api/v1/me/nodes/{id}/playback-token": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["playbackToken"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/nodes/{id}/progress": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["current"];
        put?: never;
        post: operations["record"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/videos": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/videos/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["video"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/videos/{id}/upload-target": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["reissue"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/webhooks/media": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["receive"];
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
        CreateVideoRequest: {
            /** Format: int64 */
            maxDurationSeconds?: number;
            /** Format: int64 */
            sizeBytes?: number;
        };
        IssuedUploadResponse: {
            /** Format: uuid */
            id?: string;
            state?: string;
            /** Format: date-time */
            uploadExpiresAt?: string;
            /** Format: uri */
            uploadUrl?: string;
        };
        LearnerProgress: {
            allowSeekForward?: boolean;
            approximate?: boolean;
            completed?: boolean;
            /** Format: date-time */
            completedAt?: string;
            completionSource?: string;
            /** Format: int32 */
            coveredSeconds?: number;
            /** Format: int32 */
            extentSeconds?: number;
            /** Format: int32 */
            fragments?: number;
            /** Format: uuid */
            nodeId?: string;
            /** Format: int32 */
            percent?: number;
            /** Format: int32 */
            resumeSecond?: number;
            /** Format: int32 */
            seekCeilingSecond?: number;
            /** Format: int32 */
            thresholdPercent?: number;
        };
        PlaybackTokenView: {
            /** Format: date-time */
            expiresAt?: string;
            /** Format: uri */
            manifestUrl?: string;
            /** Format: uuid */
            nodeId?: string;
            /** Format: date-time */
            renewAfter?: string;
            token?: string;
            /** Format: uuid */
            videoAssetId?: string;
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
        ProgressBatch: {
            playbackToken?: string;
            samples?: components["schemas"]["Sample"][];
        };
        Sample: {
            /** Format: int32 */
            fromSecond?: number;
            /** Format: date-time */
            observedAt?: string;
            /** Format: double */
            rate?: number;
            /** Format: int32 */
            toSecond?: number;
        };
        VideoView: {
            /** Format: double */
            durationSeconds?: number;
            /** Format: uuid */
            id?: string;
            /** Format: int64 */
            sizeBytes?: number;
            state?: string;
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
    playbackToken: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description A token, where to play it from, and when to come back for the next one. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PlaybackTokenView"];
                };
            };
            /** @description Refused with a reason the caller may know: `ACCOUNT_SUSPENDED`, `ACCOUNT_READ_ONLY` or `CONTENT_GATED` — the last carrying the gate's own sentence in `detail`, because T-5.3 requires a rule to be readable by the learner it stops. */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description **No body, deliberately.** No permission, not assigned, and no such node are one indistinguishable answer: a caller must not be able to tell "not yours" from "does not exist". Which of the three it was is in the audit log, and nowhere a caller can read. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description `NOT_PLAYABLE`. The asset exists and is not ready to be watched. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description `PLAYBACK_RATE_LIMITED`. Twenty mints per five minutes per person; a player renewing on schedule never reaches it. */
            429: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    current: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Where this learner is, and whether this item allows skipping ahead. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LearnerProgress"];
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
            /** @description `LEARNER_UNRESOLVED`. Identity could not name the caller; try again. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    record: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ProgressBatch"];
            };
        };
        responses: {
            /** @description The merged coverage after this batch, which is what the player renders. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LearnerProgress"];
                };
            };
            /** @description Nothing was credited and resending will not change that: `EMPTY_BATCH`, `MALFORMED_INTERVAL`, `IMPLAUSIBLE_RATE` or `MISSING_ATTRIBUTION`. */
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
            /** @description `SEEK_NOT_ALLOWED`. The item forbids skipping ahead, and the rule was one the player had already been told about. */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description `BATCH_TOO_LARGE`. Split it and post the halves. */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description `LEARNER_UNRESOLVED`. Nothing is wrong with the request and nothing has been lost: identity could not name the caller, so keep the samples and post them again. A 5xx rather than a 4xx precisely so a client retries. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    create: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateVideoRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["IssuedUploadResponse"];
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
    video: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
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
                    "*/*": components["schemas"]["VideoView"];
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
    reissue: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
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
                    "*/*": components["schemas"]["IssuedUploadResponse"];
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
    receive: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": string;
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}
