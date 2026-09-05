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
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PlaybackTokenView"];
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
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LearnerProgress"];
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
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LearnerProgress"];
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
