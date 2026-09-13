export interface paths {
    "/api/v1/assignments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["all_2"];
        put?: never;
        post: operations["grant"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/assignments/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["revoke"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth-info": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["authInfo"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/discovery": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["discover"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["roots"];
        put?: never;
        post: operations["create_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["delete_1"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups/{id}/children": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["children"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups/{id}/members/{userId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["addMember"];
        delete: operations["removeMember"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups/{id}/parent": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["move"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/groups/{id}/reach": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["reach_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/impersonations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["all_3"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
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
    "/api/v1/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["me"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/reach/{resource}/{action}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["reach"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/impersonations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["mine"];
        put?: never;
        post: operations["start"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/impersonations/{sessionId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["end"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/tenants": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["all_1"];
        put?: never;
        post: operations["provision"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/platform/tenants/{tenantId}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["setStatus"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/roles": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["all"];
        put?: never;
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/roles/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_1"];
        put: operations["rename"];
        post?: never;
        delete: operations["delete"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/roles/{id}/clone": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["clone"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/roles/{id}/permissions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["setPermissions"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/roles/{roleId}/assignments": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["ofRole"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sso/domains": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["domains"];
        put?: never;
        post: operations["claim"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sso/domains/{id}/verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["verify"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sso/providers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["providers"];
        put?: never;
        post: operations["register"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sso/providers/{alias}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["unregister"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/import": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["importUsers"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/invitations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["invite"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/invitations/accept": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["accept"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/preferences": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get"];
        put: operations["put"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me/timezone": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["moveTo"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["user"];
        put: operations["update"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/{id}/deactivate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["deactivate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/{id}/reactivate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["reactivate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/{id}/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["status"];
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
        AcceptRequest: {
            token?: string;
        };
        AssignmentView: {
            /** Format: uuid */
            grantedBy?: string;
            /** Format: uuid */
            groupId?: string;
            /** Format: uuid */
            id?: string;
            /** Format: uuid */
            roleId?: string;
            /** Format: uuid */
            scopeId?: string;
            scopeType?: string;
            /** Format: uuid */
            userId?: string;
        };
        CreateGroupRequest: {
            name?: string;
            /** Format: uuid */
            parentId?: string;
        };
        CreateRoleRequest: {
            description?: string;
            name?: string;
        };
        DiscoveryRequest: {
            email?: string;
        };
        DiscoveryView: {
            displayName?: string;
            provider?: string;
        };
        DomainRequest: {
            domain?: string;
        };
        DomainView: {
            dnsName?: string;
            domain?: string;
            /** Format: uuid */
            id?: string;
            txtValue?: string;
            verified?: boolean;
            /** Format: date-time */
            verifiedAt?: string;
        };
        GrantRequest: {
            /** Format: uuid */
            groupId?: string;
            /** Format: uuid */
            roleId?: string;
            /** Format: uuid */
            scopeId?: string;
            scopeType?: string;
            /** Format: uuid */
            userId?: string;
        };
        GroupReach: {
            groupIds?: string[];
            userIds?: string[];
        };
        GroupView: {
            /** Format: uuid */
            id?: string;
            name?: string;
            /** Format: uuid */
            parentId?: string;
        };
        ImportReport: {
            dryRun?: boolean;
            /** Format: int32 */
            failed?: number;
            /** Format: int32 */
            invited?: number;
            /** Format: int32 */
            reinvited?: number;
            rows?: components["schemas"]["ImportRow"][];
            /** Format: int32 */
            skipped?: number;
        };
        ImportRow: {
            /** @enum {string} */
            action?: "INVITE" | "REINVITE" | "SKIP" | "ERROR";
            detail?: string;
            email?: string;
            /** Format: int32 */
            line?: number;
            token?: string;
        };
        InvitationView: {
            displayName?: string;
            email?: string;
            /** Format: date-time */
            expiresAt?: string;
            token?: string;
            /** Format: uuid */
            userId?: string;
        };
        InviteRequest: {
            displayName?: string;
            email?: string;
        };
        Me: {
            displayName?: string;
            email?: string;
            /** Format: uuid */
            id?: string;
            language?: string;
            status?: string;
            tenant?: string;
            theme?: string;
        };
        MoveGroupRequest: {
            /** Format: uuid */
            parentId?: string;
        };
        PermissionsRequest: {
            permissions?: string[];
        };
        PersonView: {
            /** Format: date-time */
            deactivatedAt?: string;
            displayName?: string;
            email?: string;
            /** Format: uuid */
            id?: string;
            status?: string;
            timeZone?: string;
        };
        Preferences: {
            language?: string;
            theme?: string;
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
        ProviderRequest: {
            alias?: string;
            clientId?: string;
            clientSecret?: string;
            displayName?: string;
            issuer?: string;
            kind?: string;
            metadataUrl?: string;
        };
        ProviderView: {
            alias?: string;
            applied?: boolean;
            /** Format: date-time */
            appliedAt?: string;
            displayName?: string;
            kind?: string;
        };
        ProvisionRequest: {
            adminEmail?: string;
            adminName?: string;
            name?: string;
            tenantId?: string;
        };
        ProvisionedView: {
            adminEmail?: string;
            /** Format: uuid */
            adminUserId?: string;
            name?: string;
            tenantId?: string;
        };
        ReachView: {
            bankIds?: string[];
            courseIds?: string[];
            groupIds?: string[];
            permission?: string;
            wholeTenant?: boolean;
        };
        RenameRoleRequest: {
            description?: string;
            name?: string;
        };
        RoleView: {
            description?: string;
            /** Format: uuid */
            id?: string;
            name?: string;
            permissions?: string[];
            side?: string;
            system?: boolean;
        };
        SessionView: {
            actorEmail?: string;
            actorName?: string;
            /** Format: uuid */
            actorUserId?: string;
            /** Format: date-time */
            endedAt?: string;
            endedReason?: string;
            /** Format: date-time */
            expiresAt?: string;
            /** Format: uuid */
            id?: string;
            impersonatedName?: string;
            /** Format: uuid */
            impersonatedUserId?: string;
            reason?: string;
            /** Format: date-time */
            startedAt?: string;
            tenantId?: string;
            writable?: boolean;
        };
        StartRequest: {
            reason?: string;
            tenantId?: string;
            /** Format: uuid */
            userId?: string;
            writable?: boolean;
        };
        StartedView: {
            /** Format: date-time */
            expiresAt?: string;
            header?: string;
            /** Format: uuid */
            impersonatedUserId?: string;
            /** Format: uuid */
            sessionId?: string;
            tenantId?: string;
            writable?: boolean;
        };
        StatusRequest: {
            reason?: string;
            status?: string;
        };
        TenantView: {
            archived?: boolean;
            name?: string;
            status?: string;
            tenantId?: string;
        };
        TimeZoneRequest: {
            timeZone?: string;
        };
        UpdateUserRequest: {
            displayName?: string;
            email?: string;
        };
        UserSummary: {
            displayName?: string;
            /** Format: uuid */
            id?: string;
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
    all_2: {
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
                    "*/*": components["schemas"]["AssignmentView"][];
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
    grant: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["GrantRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AssignmentView"];
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
    revoke: {
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
                content?: never;
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
    authInfo: {
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
    discover: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DiscoveryRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DiscoveryView"];
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
    roots: {
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
                    "*/*": components["schemas"]["GroupView"][];
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
    create_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateGroupRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["GroupView"];
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
    delete_1: {
        parameters: {
            query?: {
                rehome?: boolean;
            };
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
                content?: never;
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
    children: {
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
                    "*/*": components["schemas"]["GroupView"][];
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
    addMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                userId: string;
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
                content?: never;
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
    removeMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                userId: string;
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
                content?: never;
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
    move: {
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
                "application/json": components["schemas"]["MoveGroupRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["GroupView"];
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
    reach_1: {
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
                    "*/*": components["schemas"]["GroupReach"];
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
    all_3: {
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
                    "*/*": components["schemas"]["SessionView"][];
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
    me: {
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
                    "*/*": components["schemas"]["Me"];
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
    reach: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                resource: string;
                action: string;
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
                    "*/*": components["schemas"]["ReachView"];
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
    mine: {
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
                    "*/*": components["schemas"]["SessionView"][];
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
    start: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StartRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["StartedView"];
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
    end: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                sessionId: string;
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
                content?: never;
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
    all_1: {
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
                    "*/*": components["schemas"]["TenantView"][];
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
    provision: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ProvisionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ProvisionedView"];
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
    setStatus: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                tenantId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["StatusRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TenantView"];
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
    all: {
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
                    "*/*": components["schemas"]["RoleView"][];
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
                "application/json": components["schemas"]["CreateRoleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RoleView"];
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
    get_1: {
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
                    "*/*": components["schemas"]["RoleView"];
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
    rename: {
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
                "application/json": components["schemas"]["RenameRoleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RoleView"];
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
            query?: {
                cascade?: boolean;
            };
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
                content?: never;
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
    clone: {
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
                "application/json": components["schemas"]["CreateRoleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RoleView"];
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
    setPermissions: {
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
                "application/json": components["schemas"]["PermissionsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RoleView"];
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
    ofRole: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                roleId: string;
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
                    "*/*": components["schemas"]["AssignmentView"][];
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
    domains: {
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
                    "*/*": components["schemas"]["DomainView"][];
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
    claim: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DomainRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DomainView"];
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
    verify: {
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
                    "*/*": components["schemas"]["DomainView"];
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
    providers: {
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
                    "*/*": components["schemas"]["ProviderView"][];
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
    register: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ProviderRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ProviderView"];
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
    unregister: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                alias: string;
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
                content?: never;
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
    importUsers: {
        parameters: {
            query?: {
                dryRun?: boolean;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "text/csv": string;
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ImportReport"];
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
    invite: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InviteRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["InvitationView"];
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
    accept: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AcceptRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PersonView"];
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
    get: {
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
                    "*/*": components["schemas"]["Preferences"];
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
    put: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Preferences"];
            };
        };
        responses: {
            /** @description The preferences as they now stand */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Preferences"];
                };
            };
            /** @description A theme this product does not have, or a string that is not a language tag */
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
        };
    };
    moveTo: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TimeZoneRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PersonView"];
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
    user: {
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
                    "*/*": components["schemas"]["UserSummary"];
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
    update: {
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
                "application/json": components["schemas"]["UpdateUserRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PersonView"];
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
    deactivate: {
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
                    "*/*": components["schemas"]["PersonView"];
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
    reactivate: {
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
                    "*/*": components["schemas"]["PersonView"];
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
    status: {
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
                    "*/*": components["schemas"]["PersonView"];
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
}
