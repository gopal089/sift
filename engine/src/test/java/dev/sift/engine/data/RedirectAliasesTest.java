package dev.sift.engine.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sift.engine.data.RedirectAliases.Redirect;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedirectAliasesTest {

    @Test
    void directRedirectsBecomeAliasesInFileOrder() {
        RedirectAliases a = RedirectAliases.of(List.of(
                new Redirect("USA", "United States"),
                new Redirect("U.S.", "United States"),
                new Redirect("WW2", "World War II")));
        assertEquals(List.of("USA", "U.S."), a.aliasesOf("United States"));
        assertEquals(List.of("WW2"), a.aliasesOf("World War II"));
        assertEquals(List.of(), a.aliasesOf("Chess"));
        assertEquals(3, a.size());
    }

    @Test
    void chainsAreFollowedToTheFinalTarget() {
        // A → B → C: both A and B are names for C
        RedirectAliases a = RedirectAliases.of(List.of(
                new Redirect("A", "B"),
                new Redirect("B", "C")));
        assertEquals(List.of("A", "B"), a.aliasesOf("C"));
        assertEquals(List.of(), a.aliasesOf("B"));
    }

    @Test
    void loopsAndSelfRedirectsAreDropped() {
        RedirectAliases a = RedirectAliases.of(List.of(
                new Redirect("X", "Y"),
                new Redirect("Y", "X"),
                new Redirect("Same", "Same")));
        assertEquals(0, a.size());
    }
}
