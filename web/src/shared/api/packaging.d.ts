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
    "/api/v1/me/runtime/{packageId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["open"];
        put: operations["save"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/uploads": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list"];
        put?: never;
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/uploads/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["one"];
        put?: never;
        post?: never;
        delete: operations["delete"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/uploads/{id}/ingest": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["ingest"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/served/{tenantId}/{packageId}/files/**": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["file"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/served/{tenantId}/{packageId}/launch": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["launch"];
        put?: never;
        post?: never;
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
        CreateUploadRequest: {
            filename?: string;
            kind?: string;
            /** Format: int64 */
            sizeBytes?: number;
        };
        IssuedUploadView: {
            /** Format: uuid */
            id?: string;
            state?: string;
            /** Format: date-time */
            uploadExpiresAt?: string;
            /** Format: uri */
            uploadUrl?: string;
        };
        PackageView: {
            contentOrigin?: string;
            /** Format: date-time */
            createdAt?: string;
            entryPath?: string;
            error?: string;
            /** Format: int32 */
            fileCount?: number;
            /** Format: uuid */
            id?: string;
            kind?: string;
            launchUrl?: string;
            profile?: string;
            sha256?: string;
            /** Format: int64 */
            sourceBytes?: number;
            sourceName?: string;
            state?: string;
            title?: string;
            /** Format: int64 */
            unpackedBytes?: number;
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
        RuntimeView: {
            completed?: boolean;
            /** Format: date-time */
            completedAt?: string;
            contentOrigin?: string;
            data?: {
                [key: string]: string;
            };
            entry?: string;
            launchUrl?: string;
            /** Format: int32 */
            launches?: number;
            /** Format: uuid */
            nodeId?: string;
            /** Format: uuid */
            packageId?: string;
            passed?: boolean;
            profile?: string;
            scoreRaw?: number;
            /** Format: int32 */
            secondsSpent?: number;
            /** Format: uuid */
            session?: string;
            /** Format: int32 */
            sessionSeconds?: number;
            /** Format: int32 */
            totalSeconds?: number;
        };
        SaveRequest: {
            /** Format: int32 */
            addedSeconds?: number;
            data?: {
                [key: string]: string;
            };
            /** Format: uuid */
            session?: string;
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
    open: {
        parameters: {
            query?: {
                nodeId?: string;
            };
            header?: never;
            path: {
                packageId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Their place in this package */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RuntimeView"];
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
            /** @description The package is not ready to be opened */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    save: {
        parameters: {
            query?: {
                nodeId?: string;
            };
            header?: never;
            path: {
                packageId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SaveRequest"];
            };
        };
        responses: {
            /** @description Saved, with what this platform derived from it */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RuntimeView"];
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
            /** @description Another launch has taken this registration, so this one has stopped saving */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description More data than one runtime may hold */
            413: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description This registration is over its write budget. Nothing is lost: the next save carries the whole data model again */
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
    list: {
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
                    "*/*": components["schemas"]["PackageView"][];
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
    create: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateUploadRequest"];
            };
        };
        responses: {
            /** @description The package is reserved and the target issued */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["IssuedUploadView"];
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
            /** @description The declared size is past the per-package ceiling */
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
    one: {
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
                    "*/*": components["schemas"]["PackageView"];
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
    delete: {
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
            /** @description Accepted. `state` is DELETED once the objects are actually gone, and DELETING while they are not */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PackageView"];
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
    ingest: {
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
            /** @description The archive was processed. `state` is READY, REJECTED or FAILED; on the last two, `error` says why */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PackageView"];
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
            /** @description Nothing has been uploaded for this package yet */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    file: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                tenantId: string;
                packageId: string;
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
                    "*/*": string;
                };
            };
        };
    };
    launch: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                tenantId: string;
                packageId: string;
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
                    "text/html": string;
                };
            };
        };
    };
}
