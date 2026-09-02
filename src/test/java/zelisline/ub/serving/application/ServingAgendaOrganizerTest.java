package zelisline.ub.serving.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ServingAgendaOrganizerTest {

    @Test
    void heuristicSplitsNumberedAsks() {
        String transcript = """
                TENANT: 1. The till is stuck on splash
                TENANT: 2. Can you point a custom domain?
                TENANT: 3. We also need SMS credits
                """;
        List<ServingAgendaOrganizer.DraftPoint> points = ServingAgendaOrganizer.heuristic(transcript, "Help");
        assertEquals(3, points.size());
        assertTrue(points.get(0).title().toLowerCase().contains("till")
                || points.get(0).detail().toLowerCase().contains("till"));
        assertTrue(points.get(1).title().toLowerCase().contains("domain")
                || points.get(1).detail().toLowerCase().contains("domain"));
        assertTrue(points.get(2).title().toLowerCase().contains("sms")
                || points.get(2).detail().toLowerCase().contains("sms"));
    }

    @Test
    void heuristicSplitsInlineNumberedList() {
        List<ServingAgendaOrganizer.DraftPoint> points = ServingAgendaOrganizer.heuristic(
                "TENANT: 1. Till is stuck on the splash. 2. Please point a custom domain. 3. We also need SMS credits.",
                "Help"
        );
        assertEquals(3, points.size());
    }

    @Test
    void heuristicSplitsQuestions() {
        List<ServingAgendaOrganizer.DraftPoint> points = ServingAgendaOrganizer.heuristic(
                "Why is the splash stuck? Also can you add a custom domain for the shop?",
                "Support"
        );
        assertTrue(points.size() >= 2);
    }

    @Test
    void extractJsonObjectStripsFence() {
        String json = ServingAgendaOrganizer.extractJsonObject("""
                ```json
                {"points":[{"title":"Till","detail":"Stuck"}]}
                ```
                """);
        assertTrue(json.contains("\"title\":\"Till\""));
    }
}
