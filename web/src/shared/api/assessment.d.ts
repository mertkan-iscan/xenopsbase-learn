export interface paths {
    "/api/v1/banks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list"];
        put?: never;
        post: operations["create_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/banks/{bankId}/questions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_1"];
        put?: never;
        post: operations["create_2"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/banks/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_1"];
        put: operations["rename_1"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/grading/attempts/{attemptId}/answers/{responseId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["mark"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/grading/attempts/{attemptId}/history": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["history_2"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/grading/attempts/{attemptId}/marks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["marks"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/grading/attempts/{attemptId}/signals": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["signals"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/grading/questions/{questionId}/rubric": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["rubric"];
        put?: never;
        post: operations["addCriterion"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/grading/queue": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["waiting"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/grading/queue/depth": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["depth"];
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
    "/api/v1/me/attempts/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["one_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/attempts/{id}/answers/{formItemId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["answer"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/attempts/{id}/review": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["review_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/attempts/{id}/signals": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["signal"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/attempts/{id}/submit": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["submit"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/monitoring": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["monitoring"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/me/tests/{testId}/attempts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["history"];
        put?: never;
        post: operations["start"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/questions/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get"];
        put: operations["edit"];
        post?: never;
        delete: operations["delete_1"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/questions/{id}/bank": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["move_1"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/questions/{id}/description": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["description"];
        put: operations["describe"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/questions/{id}/versions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["history_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/questions/{id}/versions/{versionId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["version"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/questions/{id}/versions/{versionId}/authoring": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["authoring"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sections/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["remove"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sections/{id}/pool": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["pool"];
        put: operations["pool_1"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sections/{id}/position": {
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
    "/api/v1/sections/{id}/questions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["questions"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sections/{id}/scoring": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["scoring_1"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sections/{id}/shuffle": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["shuffle"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/sections/{id}/weight": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["weight"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/shared-banks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["shared"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/shared-banks/{id}/copies": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["copy"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tests": {
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
    "/api/v1/tests/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["one"];
        put: operations["rename"];
        post?: never;
        delete: operations["delete"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tests/{id}/review": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["review"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tests/{id}/scoring": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["scoring"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tests/{id}/sitting": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["sitting"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/tests/{testId}/sections": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["of"];
        put?: never;
        post: operations["add"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/vocabulary/difficulties": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["difficulties"];
        put?: never;
        post: operations["addDifficulty"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/vocabulary/tags": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["tags"];
        put?: never;
        post: operations["addTag"];
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
        AnswerForm: {
            response?: components["schemas"]["JsonNode"];
        };
        AttemptGradingView: {
            /** Format: uuid */
            attemptId?: string;
            /** Format: date-time */
            gradedAt?: string;
            grading?: string;
            passed?: boolean;
            /** Format: int32 */
            scorePercent?: number;
            scoreRaw?: number;
            scoreScaled?: number;
            state?: string;
        };
        AttemptView: {
            /** Format: int32 */
            attemptNumber?: number;
            /** Format: date-time */
            expiresAt?: string;
            /** Format: uuid */
            id?: string;
            /** Format: date-time */
            startedAt?: string;
            state?: string;
            /** Format: date-time */
            submittedAt?: string;
        };
        BankForm: {
            /** Format: uuid */
            bankId?: string;
        };
        BankView: {
            /** Format: uuid */
            copiedFromBankId?: string;
            /** Format: date-time */
            createdAt?: string;
            description?: string;
            /** Format: uuid */
            id?: string;
            name?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        CopyForm: {
            description?: string;
            name?: string;
        };
        CreateForm: {
            body?: components["schemas"]["JsonNode"];
            internalName?: string;
        };
        CriterionForm: {
            maxPoints?: number;
            name?: string;
            /** Format: int32 */
            ordinal?: number;
        };
        CriterionView: {
            /** Format: uuid */
            id?: string;
            maxPoints?: number;
            name?: string;
            /** Format: int32 */
            ordinal?: number;
        };
        DeletionView: {
            explanation?: string;
            retired?: boolean;
        };
        DescriptionForm: {
            /** Format: uuid */
            difficultyId?: string;
            tagIds?: string[];
        };
        DescriptionView: {
            /** Format: uuid */
            difficultyId?: string;
            tagIds?: string[];
        };
        DifficultyForm: {
            code?: string;
            /** Format: int32 */
            rank?: number;
        };
        DifficultyView: {
            code?: string;
            /** Format: int32 */
            rank?: number;
        };
        EditForm: {
            body?: components["schemas"]["JsonNode"];
            internalName?: string;
        };
        EventView: {
            /** Format: date-time */
            at?: string;
            /** Format: uuid */
            gradedBy?: string;
            grading?: string;
            /** Format: uuid */
            id?: string;
            note?: string;
            passed?: boolean;
            /** Format: int32 */
            scorePercent?: number;
            scoreRaw?: number;
            scoreScaled?: number;
        };
        ItemView: {
            /** Format: uuid */
            formItemId?: string;
            optionOrder?: {
                [key: string]: string[];
            };
            /** Format: int32 */
            position?: number;
            /** Format: uuid */
            questionVersionId?: string;
            /** Format: uuid */
            sectionId?: string;
        };
        JsonNode: {
            array?: boolean;
            bigDecimal?: boolean;
            bigInteger?: boolean;
            binary?: boolean;
            boolean?: boolean;
            container?: boolean;
            double?: boolean;
            embeddedValue?: boolean;
            empty?: boolean;
            float?: boolean;
            floatingPointNumber?: boolean;
            int?: boolean;
            integralNumber?: boolean;
            long?: boolean;
            missingNode?: boolean;
            /** @enum {string} */
            nodeType?: "ARRAY" | "BINARY" | "BOOLEAN" | "MISSING" | "NULL" | "NUMBER" | "OBJECT" | "POJO" | "STRING";
            null?: boolean;
            number?: boolean;
            object?: boolean;
            pojo?: boolean;
            short?: boolean;
            string?: boolean;
            /** @deprecated */
            textual?: boolean;
            valueNode?: boolean;
        };
        MarkForm: {
            awarded?: number;
            comment?: string;
            criterionMarks?: {
                [key: string]: number;
            };
            note?: string;
        };
        MarkView: {
            /** Format: int32 */
            available?: number;
            awarded?: number;
            comment?: string;
            /** Format: int32 */
            credited?: number;
            criterionMarks?: {
                [key: string]: number;
            };
            /** Format: uuid */
            formItemId?: string;
            graded?: boolean;
            /** Format: uuid */
            gradedBy?: string;
            /** Format: uuid */
            questionVersionId?: string;
            /** Format: uuid */
            responseId?: string;
        };
        MonitoringView: {
            collects?: string[];
            /** Format: int64 */
            keptForDays?: number;
            neverUsedFor?: string;
            usedFor?: string;
        };
        MoveForm: {
            /** Format: uuid */
            afterSectionId?: string;
        };
        NewSectionForm: {
            /** Format: int32 */
            drawCount?: number;
            selection?: string;
            title?: string;
        };
        PoolForm: {
            /** Format: uuid */
            bankId?: string;
            /** Format: int32 */
            drawCount?: number;
            /** Format: int32 */
            maxDifficultyRank?: number;
            /** Format: int32 */
            minDifficultyRank?: number;
            tagIds?: string[];
        };
        PoolView: {
            /** Format: int32 */
            available?: number;
            enough?: boolean;
            /** Format: int32 */
            wanted?: number;
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
        QuestionView: {
            /** Format: uuid */
            bankId?: string;
            /** Format: date-time */
            createdAt?: string;
            currentVersion?: components["schemas"]["VersionView"];
            /** Format: uuid */
            id?: string;
            internalName?: string;
            /** Format: date-time */
            retiredAt?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        QuestionsForm: {
            questionIds?: string[];
        };
        Review: {
            /** Format: uuid */
            attemptId?: string;
            /** Format: int32 */
            attemptNumber?: number;
            grading?: string;
            items?: components["schemas"]["ReviewedItem"][];
            passed?: boolean;
            /** Format: int32 */
            scorePercent?: number;
            scoreRaw?: number;
            scoreScaled?: number;
            state?: string;
            /** Format: date-time */
            submittedAt?: string;
            /** Format: uuid */
            testId?: string;
            visibility?: string;
        };
        ReviewForm: {
            /** Format: date-time */
            openAt?: string;
            timing?: string;
            visibility?: string;
        };
        ReviewedItem: {
            awarded?: number;
            body?: components["schemas"]["JsonNode"];
            correct?: boolean;
            feedback?: string;
            /** Format: uuid */
            formItemId?: string;
            graderComment?: string;
            optionOrder?: {
                [key: string]: string[];
            };
            points?: number;
            /** Format: int32 */
            position?: number;
            /** Format: uuid */
            questionVersionId?: string;
            response?: components["schemas"]["JsonNode"];
        };
        ScoringForm: {
            mode?: string;
            points?: number;
        };
        ScoringRequest: {
            defaultMode?: string;
            defaultPoints?: number;
            negativeMarking?: boolean;
            /** Format: int32 */
            passMarkPercent?: number;
            penaltyPoints?: number;
        };
        SectionView: {
            /** Format: uuid */
            bankId?: string;
            /** Format: int32 */
            drawCount?: number;
            /** Format: uuid */
            id?: string;
            /** Format: int32 */
            maxDifficultyRank?: number;
            /** Format: int32 */
            minDifficultyRank?: number;
            mode?: string;
            points?: number;
            questionIds?: string[];
            selection?: string;
            shuffleOptions?: boolean;
            shuffleQuestions?: boolean;
            tagIds?: string[];
            /** Format: uuid */
            testId?: string;
            title?: string;
            /** Format: int32 */
            weight?: number;
        };
        SharedBank: {
            description?: string;
            /** Format: uuid */
            id?: string;
            name?: string;
        };
        ShuffleForm: {
            options?: boolean;
            questions?: boolean;
        };
        SignalForm: {
            detail?: components["schemas"]["JsonNode"];
            kind?: string;
            /** Format: date-time */
            reportedAt?: string;
        };
        SignalView: {
            detail?: components["schemas"]["JsonNode"];
            /** Format: uuid */
            id?: string;
            kind?: string;
            /** Format: date-time */
            recordedAt?: string;
            /** Format: date-time */
            reportedAt?: string;
            whatItMeans?: string;
        };
        SittingForm: {
            /** Format: int32 */
            attemptsAllowed?: number;
            /** Format: int32 */
            timeLimitSeconds?: number;
        };
        SittingView: {
            answers?: {
                [key: string]: components["schemas"]["JsonNode"];
            };
            /** Format: uuid */
            attemptId?: string;
            /** Format: int32 */
            attemptNumber?: number;
            /** Format: date-time */
            expiresAt?: string;
            items?: components["schemas"]["ItemView"][];
            monitoring?: components["schemas"]["MonitoringView"];
            /** Format: int64 */
            secondsRemaining?: number;
            /** Format: date-time */
            startedAt?: string;
            state?: string;
            /** Format: date-time */
            submittedAt?: string;
            /** Format: uuid */
            testId?: string;
        };
        TagForm: {
            tag?: string;
        };
        TagView: {
            tag?: string;
        };
        TestRequest: {
            description?: string;
            /** Format: int32 */
            passMarkPercent?: number;
            title?: string;
        };
        TestView: {
            /** Format: int32 */
            attemptsAllowed?: number;
            defaultMode?: string;
            defaultPoints?: number;
            description?: string;
            /** Format: uuid */
            id?: string;
            negativeMarking?: boolean;
            /** Format: int32 */
            passMarkPercent?: number;
            penaltyPoints?: number;
            /** Format: date-time */
            reviewAfter?: string;
            reviewTiming?: string;
            reviewVisibility?: string;
            /** Format: int32 */
            timeLimitSeconds?: number;
            title?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        VersionView: {
            body?: components["schemas"]["JsonNode"];
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            firstServedAt?: string;
            /** Format: uuid */
            id?: string;
            /** Format: int32 */
            version?: number;
        };
        WaitingView: {
            /** Format: uuid */
            attemptId?: string;
            /** Format: int32 */
            attemptNumber?: number;
            /** Format: uuid */
            learnerId?: string;
            /** Format: int32 */
            outstanding?: number;
            /** Format: date-time */
            submittedAt?: string;
            /** Format: uuid */
            testId?: string;
            testTitle?: string;
            /** Format: int64 */
            waitingSeconds?: number;
        };
        WeightForm: {
            /** Format: int32 */
            weight?: number;
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
                    "*/*": components["schemas"]["BankView"][];
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
                "application/json": components["schemas"]["BankForm"];
            };
        };
        responses: {
            /** @description The bank that was created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BankView"];
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
            /** @description This company already has a bank with that name */
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
    list_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bankId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The bank's questions, retired ones excluded */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["QuestionView"][];
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
            /** @description No such bank in this company */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    create_2: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                bankId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateForm"];
            };
        };
        responses: {
            /** @description The question, on its first version */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["QuestionView"];
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
            /** @description No such bank in this company */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
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
            /** @description The bank */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BankView"];
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
            /** @description No such bank in this company */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    rename_1: {
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
                "application/json": components["schemas"]["BankForm"];
            };
        };
        responses: {
            /** @description The bank as it now is */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BankView"];
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
            /** @description No such bank in this company */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description This company already has a bank with that name */
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
    mark: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                attemptId: string;
                responseId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MarkForm"];
            };
        };
        responses: {
            /** @description The attempt as it now stands. It settles when nothing is left outstanding. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AttemptGradingView"];
                };
            };
            /** @description A mark that does not fit the rubric, exceeds what the question is worth, or is negative. */
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
            /** @description No such attempt in this company, or it has no such answer. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description The attempt is still being sat. */
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
    history_2: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                attemptId: string;
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
                    "*/*": components["schemas"]["EventView"][];
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
    marks: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                attemptId: string;
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
                    "*/*": components["schemas"]["MarkView"][];
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
    signals: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                attemptId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description What the learner's browser reported during the attempt, in the order it reached us. Self-reported telemetry from a page the learner controls: it corroborates a human's suspicion and is not evidence, and an ABSENCE of signals means nothing at all. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SignalView"][];
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
    rubric: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                questionId: string;
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
                    "*/*": components["schemas"]["CriterionView"][];
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
    addCriterion: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                questionId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CriterionForm"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CriterionView"];
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
    waiting: {
        parameters: {
            query?: {
                testId?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Attempts waiting for a person, oldest first, with how long each has waited. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["WaitingView"][];
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
    depth: {
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
                        [key: string]: number;
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
    one_1: {
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
            /** @description The attempt, its form and what is saved. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SittingView"];
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
            /** @description No such attempt, or it is not this caller's — the same answer either way (T-2.4's disclosure rule). */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    answer: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                formItemId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AnswerForm"];
            };
        };
        responses: {
            /** @description Saved. Repeating it is free. */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description The response does not fit the question this learner was served (T-6.3). */
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
            /** @description No such attempt for this caller, or it was never asked that question. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description Time is up, or the attempt is already over. Everything saved before the deadline still counts. */
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
    review_1: {
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
            /** @description The result, and as much of the paper as the policy permits. `visibility` says which — a screen renders the shape it names rather than inferring one from which fields happen to be null. Answer keys and feedback are absent from the payload unless the policy is FULL and its timing has opened. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Review"];
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
            /** @description No such attempt, or it is not this caller's — the same answer either way. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    signal: {
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
                "application/json": components["schemas"]["SignalForm"];
            };
        };
        responses: {
            /** @description Taken. Also the answer when it was dropped, which the client does not need to distinguish. */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Not one of the disclosed kinds. */
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
            /** @description No such attempt for this caller. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    submit: {
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
            /** @description The attempt, ended. Submitting again returns the same thing and changes nothing. A submit after the deadline is still accepted and the attempt is marked EXPIRED — nothing in it was written late, because the save path refuses that. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SittingView"];
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
            /** @description No such attempt for this caller. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    monitoring: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The integrity signals this platform records during an attempt, what a person may do with them, what nothing does with them, and how long they are kept. Generated from the same values the recorder accepts, so a signal cannot be collected without appearing here. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MonitoringView"];
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
    history: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                testId: string;
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
                    "*/*": components["schemas"]["AttemptView"][];
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
            path: {
                testId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The attempt and the form assembled for it. A second call while one is open resumes it rather than starting another, and does not move the deadline. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SittingView"];
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
            /** @description No such test in this company. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description Every allowed attempt has been used, or this test has no sections to sit, or a section's pool cannot fill the form it asks for (T-6.5). */
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
    get: {
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
            /** @description The question and its current version */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["QuestionView"];
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
            /** @description No such question, or it has been retired */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    edit: {
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
                "application/json": components["schemas"]["EditForm"];
            };
        };
        responses: {
            /** @description The question as it now is */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["QuestionView"];
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
            /** @description No such question, or it has been retired */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    delete_1: {
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
            /** @description Whether the question was retired or deleted */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DeletionView"];
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
            /** @description No such question, or it was already retired */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    move_1: {
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
                "application/json": components["schemas"]["BankForm"];
            };
        };
        responses: {
            /** @description The question, now in the other bank */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["QuestionView"];
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
            /** @description No such question or no such bank */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    description: {
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
            /** @description What a draw filters this question on */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DescriptionView"];
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
            /** @description No such question */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    describe: {
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
                "application/json": components["schemas"]["DescriptionForm"];
            };
        };
        responses: {
            /** @description The question's draw attributes as they now are */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DescriptionView"];
                };
            };
            /** @description A tag that is not in this company's vocabulary (T-6.1) */
            400: {
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
            /** @description No such question */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    history_1: {
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
            /** @description Every version, newest first */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["VersionView"][];
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
            /** @description No such question */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    version: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                versionId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The version, as it was served */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["VersionView"];
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
            /** @description No such question or no such version of it */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    authoring: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                versionId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The version in full, including the answer key. The endpoint a permission goes on; until grants travel between services, nothing checks one. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["VersionView"];
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
            /** @description No such question or version */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    remove: {
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
            /** @description Removed. */
            204: {
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
            /** @description Somebody has already sat a test containing this section, and their form points at it. */
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
    pool: {
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
            /** @description How many questions this section could draw from right now, against what it asks for. The same predicate the draw uses. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PoolView"];
                };
            };
            /** @description A fixed section has no pool. */
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
    pool_1: {
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
                "application/json": components["schemas"]["PoolForm"];
            };
        };
        responses: {
            /** @description The section as it now stands. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SectionView"];
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
            /** @description The pool holds fewer questions than the section asks for, with both numbers. Serving a short test would score this learner out of a different total from everybody else and nothing in the result would say so. */
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
                "application/json": components["schemas"]["MoveForm"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SectionView"];
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
    questions: {
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
                "application/json": components["schemas"]["QuestionsForm"];
            };
        };
        responses: {
            /** @description The section as it now stands. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SectionView"];
                };
            };
            /** @description A pool section draws its questions, or the list was empty, or one of the questions is not this company's. */
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
    scoring_1: {
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
                "application/json": components["schemas"]["ScoringForm"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SectionView"];
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
    shuffle: {
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
                "application/json": components["schemas"]["ShuffleForm"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SectionView"];
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
    weight: {
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
                "application/json": components["schemas"]["WeightForm"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SectionView"];
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
    shared: {
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
                    "*/*": components["schemas"]["SharedBank"][];
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
    copy: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["CopyForm"];
            };
        };
        responses: {
            /** @description The independent copy, owned by this company */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["BankView"];
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
            /** @description No such bank is offered in the shared library */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description This company already has a bank with that name */
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
                    "*/*": components["schemas"]["TestView"][];
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
                "application/json": components["schemas"]["TestRequest"];
            };
        };
        responses: {
            /** @description Created. */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TestView"];
                };
            };
            /** @description A test needs a title and a pass mark; the pass mark is a whole percent. */
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
            /** @description The test and its scoring policy. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TestView"];
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
            /** @description No such test in this company. */
            404: {
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
                "application/json": components["schemas"]["TestRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TestView"];
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
            /** @description No Content */
            204: {
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
    review: {
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
                "application/json": components["schemas"]["ReviewForm"];
            };
        };
        responses: {
            /** @description The policy as it now stands. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TestView"];
                };
            };
            /** @description A timing of AFTER_DATE with no date, a date on any other timing, or AFTER_ALL_ATTEMPTS on a test with no attempt limit — which would mean never. */
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
            /** @description No such test in this company. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    scoring: {
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
                "application/json": components["schemas"]["ScoringRequest"];
            };
        };
        responses: {
            /** @description The policy as it now stands. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TestView"];
                };
            };
            /** @description A pass mark outside 0-100, points of zero or less, a negative penalty, or an unknown scoring mode. */
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
            /** @description No such test in this company. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    sitting: {
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
                "application/json": components["schemas"]["SittingForm"];
            };
        };
        responses: {
            /** @description The policy as it now stands. */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TestView"];
                };
            };
            /** @description An attempt limit below one, or a time limit that is not positive. */
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
            /** @description No such test in this company. */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
    of: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                testId: string;
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
                    "*/*": components["schemas"]["SectionView"][];
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
    add: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                testId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NewSectionForm"];
            };
        };
        responses: {
            /** @description Created, at the end of the test. */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SectionView"];
                };
            };
            /** @description An unknown selection, or a pool section with no draw count. */
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
    difficulties: {
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
                    "*/*": components["schemas"]["DifficultyView"][];
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
    addDifficulty: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["DifficultyForm"];
            };
        };
        responses: {
            /** @description The difficulty level that was added */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DifficultyView"];
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
            /** @description This company already has that code, or already has a level at that rank */
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
    tags: {
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
                    "*/*": components["schemas"]["TagView"][];
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
    addTag: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["TagForm"];
            };
        };
        responses: {
            /** @description The tag that was added */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TagView"];
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
            /** @description This company already has that tag */
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
}
