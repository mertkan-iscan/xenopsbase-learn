package com.xenopsoftware.learn.assessment.vocabulary;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tag and difficulty vocabularies a company's questions are described with (T-6.1).
 *
 * <h2>Why there is no remove</h2>
 *
 * <p>Removing a tag or a difficulty level is only safe once something can answer whether a
 * question still uses it, and questions arrive with T-6.2. A remove written now would silently
 * orphan the references it cannot see — or, worse, appear to work, because today there is nothing
 * to break and a test would pass.
 *
 * <p>So the vocabulary is append-only until there is a referential check to run, and this
 * paragraph is the reason rather than an oversight. A vocabulary that grows and never shrinks is a
 * mild annoyance somebody can ask about; a draw whose population changed because a level was
 * deleted underneath it is an exam result nobody can explain afterwards.
 */
@Service
@Transactional
public class VocabularyService {

    private final BankTagRepository tags;
    private final BankDifficultyRepository difficulties;

    public VocabularyService(BankTagRepository tags, BankDifficultyRepository difficulties) {
        this.tags = tags;
        this.difficulties = difficulties;
    }

    @Transactional(readOnly = true)
    public List<BankTag> tags() {
        return tags.findAllByOrderByTagAsc();
    }

    public BankTag addTag(String tag) {
        String value = tag == null ? "" : tag.strip();
        tags.findByTagIgnoreCase(value).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This company already has the tag '" + existing.getTag() + "'.");
        });
        return tags.save(BankTag.of(value));
    }

    /** In order, hardest last. That order is the reason this is not simply another tag. */
    @Transactional(readOnly = true)
    public List<BankDifficulty> difficulties() {
        return difficulties.findAllByOrderByRankAsc();
    }

    public BankDifficulty addDifficulty(String code, int rank) {
        String value = code == null ? "" : code.strip();

        difficulties.findByCodeIgnoreCase(value).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This company already has a difficulty called '" + existing.getCode() + "'.");
        });

        // Guarded on the range first: an out-of-range rank is the entity's refusal to give, and
        // casting it to a short to run this query would look up a different number than the
        // caller asked about.
        if (rank >= 0 && rank <= Short.MAX_VALUE) {
            difficulties.findByRank((short) rank).ifPresent(existing -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Rank " + rank + " is already '" + existing.getCode()
                        + "'. Two levels at one rank make 'medium or harder' ambiguous at exactly "
                        + "the boundary somebody chose it for.");
            });
        }

        return difficulties.save(BankDifficulty.of(value, rank));
    }
}
