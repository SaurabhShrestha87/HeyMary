package heymary.co.integrations.util;

import heymary.co.integrations.model.RewardTier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomerNotesHelperTest {

    @Test
    void buildNotesWithPointsAndRewards_emptyUserNotes() {
        List<RewardTier> tiers = createTestRewardTiers();
        String result = CustomerNotesHelper.buildNotesWithPointsAndRewards(null, 250, tiers);

        assertTrue(result.contains("--- DO NOT EDIT"));
        assertTrue(result.contains("POINTS - 250"));
        assertTrue(result.contains("[10$ off] - 50"));
        assertTrue(result.contains("[60% off] - 100"));
        assertTrue(result.contains("[2 Free 710 Labs - Black Mamba #6 - Live Rosin Pod - Tier 2 - 1g] - 250"));
    }

    @Test
    void buildNotesWithPointsAndRewards_preservesUserNotes() {
        String userNotes = "Some random notes blah blah (Should persist after we update points etc)";
        List<RewardTier> tiers = createTestRewardTiers();
        String result = CustomerNotesHelper.buildNotesWithPointsAndRewards(userNotes, 250, tiers);

        assertTrue(result.startsWith("Some random notes blah blah"));
        assertTrue(result.contains("--- DO NOT EDIT"));
        assertTrue(result.contains("POINTS - 250"));
    }

    @Test
    void extractUserNotes_extractsContentBeforeDelimiter() {
        String notes = "User note 1\nUser note 2\n--- DO NOT EDIT\nPOINTS - 100";
        String userNotes = CustomerNotesHelper.extractUserNotes(notes);
        assertEquals("User note 1\nUser note 2", userNotes);
    }

    @Test
    void extractUserNotes_returnsFullWhenNoDelimiter() {
        String notes = "Just user notes, no delimiter";
        String userNotes = CustomerNotesHelper.extractUserNotes(notes);
        assertEquals("Just user notes, no delimiter", userNotes);
    }

    @Test
    void extractUserNotes_handlesNull() {
        assertEquals("", CustomerNotesHelper.extractUserNotes(null));
    }

    @Test
    void buildStructuredSection_sortsByThreshold() {
        List<RewardTier> tiers = createTestRewardTiers();
        String result = CustomerNotesHelper.buildStructuredSection(250, tiers);

        int idx50 = result.indexOf("[10$ off] - 50");
        int idx100 = result.indexOf("[60% off] - 100");
        int idx250 = result.indexOf("[2 Free 710 Labs");
        assertTrue(idx50 < idx100 && idx100 < idx250, "Tiers should be sorted by threshold ascending");
    }

    private List<RewardTier> createTestRewardTiers() {
        List<RewardTier> tiers = new ArrayList<>();
        tiers.add(RewardTier.builder()
                .templateId(1)
                .tierId(1)
                .name("10$ off")
                .type(2)
                .threshold(50)
                .value(BigDecimal.TEN)
                .build());
        tiers.add(RewardTier.builder()
                .templateId(1)
                .tierId(2)
                .name("60% off")
                .type(2)
                .threshold(100)
                .value(BigDecimal.valueOf(60))
                .build());
        tiers.add(RewardTier.builder()
                .templateId(1)
                .tierId(3)
                .name("2 Free 710 Labs - Black Mamba #6 - Live Rosin Pod - Tier 2 - 1g")
                .type(0)
                .threshold(250)
                .value(BigDecimal.ZERO)
                .build());
        return tiers;
    }
}
