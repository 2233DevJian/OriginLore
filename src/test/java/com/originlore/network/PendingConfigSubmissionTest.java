package com.originlore.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingConfigSubmissionTest {
    @Test
    void anotherAdministratorsBroadcastDoesNotCompleteThisSave() {
        PendingConfigSubmission pending = new PendingConfigSubmission(100);
        pending.start(10, "UPDATE", "zh_cn", 0);

        assertNull(pending.accept("SNAPSHOT", true, 11, "en_us"));
        assertTrue(pending.active());
        assertNull(pending.accept("CONFLICT", false, 11, "en_us"));
        assertFalse(pending.active());
    }

    @Test
    void onlyMatchingLanguageSaveAcknowledgesTheRequestedPreference() {
        PendingConfigSubmission pending = new PendingConfigSubmission(100);
        pending.start(10, "LANGUAGE", "en_us", 0);

        assertNull(pending.accept("SNAPSHOT", true, 11, "en_us"));
        assertNull(pending.accept("SAVED", true, 11, "en_us"));
        assertTrue(pending.active());
        assertEquals("en_us", pending.accept("LANGUAGE_SAVED", true, 11, "en_us"));
        assertFalse(pending.active());
        assertNull(pending.accept("LANGUAGE_SAVED", true, 11, "en_us"));
    }

    @Test
    void failedSaveAndDisconnectNeverChangeLanguage() {
        PendingConfigSubmission pending = new PendingConfigSubmission(100);
        pending.start(10, "LANGUAGE", "en_us", 0);
        assertNull(pending.accept("VALIDATION_ERROR", false, 10, "zh_cn"));
        assertFalse(pending.active());

        pending.start(10, "LANGUAGE", "en_us", 0);
        pending.clear();
        assertNull(pending.accept("LANGUAGE_SAVED", true, 11, "en_us"));
        assertFalse(pending.active());
    }

    @Test
    void incorrectRevisionOrLanguageCannotAcknowledgeASave() {
        PendingConfigSubmission pending = new PendingConfigSubmission(100);
        pending.start(10, "LANGUAGE", "en_us", 0);
        assertThrows(IllegalArgumentException.class, () -> pending.accept("LANGUAGE_SAVED", true, 12, "en_us"));
        assertFalse(pending.active());

        pending.start(10, "LANGUAGE", "en_us", 0);
        assertThrows(IllegalArgumentException.class, () -> pending.accept("LANGUAGE_SAVED", true, 11, "zh_cn"));
        assertFalse(pending.active());
    }

    @Test
    void interruptedUploadTimesOutButActiveDownloadExtendsTheDeadline() {
        PendingConfigSubmission pending = new PendingConfigSubmission(100);
        pending.start(10, "LANGUAGE", "en_us", 0);
        assertFalse(pending.expire(100));
        assertTrue(pending.expire(101));
        assertNull(pending.accept("LANGUAGE_SAVED", true, 11, "en_us"));

        pending.start(11, "UPDATE", "en_us", 200);
        pending.touch(290);
        assertFalse(pending.expire(350));
        assertTrue(pending.expire(391));
        assertFalse(pending.active());
    }

    @Test
    void pendingSubmissionCannotBeOverwrittenByASecondLocalEdit() {
        PendingConfigSubmission pending = new PendingConfigSubmission(100);
        pending.start(10, "UPDATE", "zh_cn", 0);
        assertThrows(IllegalStateException.class, () -> pending.start(10, "LANGUAGE", "en_us", 10));
        assertNull(pending.accept("SAVED", true, 11, "zh_cn"));
        assertFalse(pending.active());
    }

    @Test
    void confirmedSaveSurvivesLaterBroadcastUntilTheEditorConsumesIt() {
        PendingConfigSubmission pending = new PendingConfigSubmission(100);
        pending.start(10, "UPDATE", "zh_cn", 0);
        pending.accept("SAVED", true, 11, "zh_cn");
        pending.accept("SNAPSHOT", true, 12, "en_us");

        assertEquals(11, pending.acknowledgedRevision());
        pending.start(12, "UPDATE", "en_us", 10);
        assertEquals(-1, pending.acknowledgedRevision());
        pending.clear();
        assertEquals(-1, pending.acknowledgedRevision());
    }
}
