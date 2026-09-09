package com.xenopsoftware.learn.assessment.question.type;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * The two types no comparison can mark (T-6.3): essay and file upload.
 *
 * <pre>
 * essay        options   {"guidance":"Two hundred words."}    // optional
 *              answerKey {}                                    // there is none
 *              response  {"text":"..."}
 *
 * file-upload  options   {"accept":["pdf","docx"]}             // optional
 *              answerKey {}
 *              response  {"uploadId":"...."}                   // a reference, never bytes
 * </pre>
 *
 * <h2>{@code grade} returns empty, and that is the feature</h2>
 *
 * <p>Every other type answers "how much of this was right". These answer "a human has to look", and
 * saying so in the same method is what lets T-6.7 build a manual queue by asking the type rather
 * than by keeping its own list of which types are markable. A list would be the second place that
 * has to be edited when a type is added, which is exactly what T-6.3's one-dispatch-point
 * criterion is about.
 *
 * <p><b>An answer key is refused rather than ignored.</b> An author who writes a model answer into
 * the key has written something no code will ever read, and they will find out when the marks come
 * back blank. Guidance belongs in the options, where the learner sees it, or in a rubric -- which
 * is T-6.7's, along with the queue.
 *
 * <h2>A file upload's response is a reference</h2>
 *
 * <p>{@code uploadId} rather than bytes, for the reason T-3.2 gives about video: bytes must not
 * pass through a request thread of ours, and an answer that carried them would put a
 * learner-supplied file in a JSON column. The id points at whatever T-4.1's packaging or the media
 * provider is holding, and that boundary is why this type can exist before either does.
 */
@Configuration(proxyBeanMethods = false)
public class HumanGradedQuestionTypes {

    @Bean
    QuestionTypeDefinition essayQuestionType() {
        return new Essay();
    }

    @Bean
    QuestionTypeDefinition fileUploadQuestionType() {
        return new FileUpload();
    }

    private static final class Essay implements QuestionTypeDefinition {

        @Override
        public String code() {
            return "essay";
        }

        @Override
        public String displayName() {
            return "Essay";
        }

        @Override
        public void validateAsked(JsonNode options, JsonNode answerKey) {
            refuseAKey(answerKey, "An essay");
        }

        @Override
        public void validateResponse(JsonNode options, JsonNode response) {
            JsonNode text = response == null ? null : response.get("text");
            if (text != null && !text.isNull() && !text.isTextual()) {
                throw new IllegalArgumentException("An essay response's 'text' is text.");
            }
        }

        @Override
        public Optional<Correctness> grade(JsonNode options, JsonNode answerKey, JsonNode response) {
            return Optional.empty();
        }
    }

    private static final class FileUpload implements QuestionTypeDefinition {

        @Override
        public String code() {
            return "file-upload";
        }

        @Override
        public String displayName() {
            return "File upload";
        }

        @Override
        public void validateAsked(JsonNode options, JsonNode answerKey) {
            refuseAKey(answerKey, "A file upload");
            JsonNode accept = options == null ? null : options.get("accept");
            if (accept == null || accept.isNull()) {
                return;
            }
            if (!accept.isArray() || accept.isEmpty()) {
                throw new IllegalArgumentException(
                    "'accept' lists the extensions a learner may upload, and an empty list forbids "
                    + "everything. Leave it out to accept anything.");
            }
            for (JsonNode extension : accept) {
                if (!extension.isTextual() || extension.asString().isBlank()) {
                    throw new IllegalArgumentException("'accept' holds extensions, as strings.");
                }
            }
        }

        @Override
        public void validateResponse(JsonNode options, JsonNode response) {
            JsonNode upload = response == null ? null : response.get("uploadId");
            if (upload == null || upload.isNull()) {
                return;
            }
            if (!upload.isTextual() || upload.asString().isBlank()) {
                throw new IllegalArgumentException(
                    "A file-upload response points at an upload by id. The bytes never travel "
                    + "through here (T-3.2's rule, applied to answers).");
            }
        }

        @Override
        public Optional<Correctness> grade(JsonNode options, JsonNode answerKey, JsonNode response) {
            return Optional.empty();
        }
    }

    private static void refuseAKey(JsonNode answerKey, String what) {
        if (answerKey != null && !answerKey.isNull() && answerKey.isObject() && !answerKey.isEmpty()) {
            throw new IllegalArgumentException(what + " question has no answer key: nothing compares "
                + "it, a person marks it. Put what you wrote in the options as guidance, where the "
                + "learner can see it -- or in a rubric, which arrives with the marking queue.");
        }
    }
}
