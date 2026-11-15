package dev.sift.engine.card;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Real article openings from the Simple English Wikipedia dump (CC BY-SA), shortened where marked. */
class OpeningSentencesTest {

    private static String card(String title, String text) {
        return OpeningSentences.of(title, text, 12, 50);
    }

    @Test
    void oneLongSentenceIsEnough() {
        assertEquals("Albert Einstein (14 March 1879 – 18 April 1955) was a German-born Jewish scientist.",
                card("Albert Einstein", "Albert Einstein (14 March 1879 – 18 April 1955) was a German-born Jewish "
                        + "scientist. He worked on theoretical physics. He is most famous for the theory of relativity."));
    }

    @Test
    void shortFirstSentenceGetsTheSecondOne() {
        assertEquals("Tokyo is the capital and largest city of Japan. It is on the island of Honshu in the region "
                        + "of Kanto.",
                card("Tokyo", "Tokyo is the capital and largest city of Japan. It is on the island of Honshu in the "
                        + "region of Kanto. Tokyo is the center of the Japanese government."));
    }

    @Test
    void neverMoreThanTwoSentences() {
        assertEquals("The Who are an English rock band formed in 1964. The main lineup from 1964 to 1978 was "
                        + "guitarist Pete Townshend, vocalist Roger Daltrey, bassist John Entwistle and drummer Keith Moon.",
                card("The Who", "The Who are an English rock band formed in 1964. The main lineup from 1964 to 1978 "
                        + "was guitarist Pete Townshend, vocalist Roger Daltrey, bassist John Entwistle and drummer "
                        + "Keith Moon. They became known for their high energy live shows."));
    }

    @Test
    void abbreviationsAndInitialsDoNotEndASentence() {
        assertEquals("Martin Luther King, Jr. (born Michael King, Jr.; January 15, 1929 – April 4, 1968) was an "
                        + "American pastor.",
                card("Martin Luther King Jr.", "Martin Luther King, Jr. (born Michael King, Jr.; January 15, 1929 – "
                        + "April 4, 1968) was an American pastor. He was best known for improving civil rights."));
        assertEquals("J. R. R. Tolkien was an English writer who wrote The Hobbit and The Lord of the Rings.",
                card("J. R. R. Tolkien", "J. R. R. Tolkien was an English writer who wrote The Hobbit and The Lord "
                        + "of the Rings. He was born in 1892."));
        assertEquals("He was both a Ph.D. and a pastor, and he lived in the U.S. South for most of his life.",
                card("X", "He was both a Ph.D. and a pastor, and he lived in the U.S. South for most of his life. "
                        + "More text here."));
    }

    @Test
    void aYearBeforeTheFullStopStillEndsTheSentence() {
        assertEquals("Lovelace wrote the first computer program in 1844. It was for Charles Babbage's machine.",
                card("X", "Lovelace wrote the first computer program in 1844. It was for Charles Babbage's machine. "
                        + "This machine was never built."));
    }

    @Test
    void onlyTheFirstParagraphIsUsed() {
        assertEquals("Climate change refers to long-term changes in temperature, precipitation, and other "
                        + "atmospheric conditions on Earth.",
                card("Climate change", "Climate change refers to long-term changes in temperature, precipitation, "
                        + "and other atmospheric conditions on Earth.\nWhen people talk about climate change, they are "
                        + "usually talking about global warming."));
    }

    @Test
    void missingFirstWordIsReplacedByTheTitle() {
        assertEquals("Haiku is a type of Japanese poetry. Previously called hokku, haiku was given its current name "
                        + "by the Japanese writer Masaoka Shiki at the end of the 19th century.",
                card("Haiku", "is a type of Japanese poetry. Previously called hokku, haiku was given its current name "
                        + "by the Japanese writer Masaoka Shiki at the end of the 19th century.\nThe traditional hokku"));
    }

    @Test
    void longTextIsCutAtAWordWithAnEllipsis() {
        String text = "The national agency " + "does very many important things ".repeat(20) + "today.";
        String out = OpeningSentences.of("X", text, 12, 10);
        assertEquals("The national agency does very many important things does very …", out);
    }

    @Test
    void leftoversOfRemovedMarkupInBracketsAreTidied() {
        assertEquals("The Great Wall of China (literally \"ten thousand li long wall\") is a series of fortifications "
                        + "in China.",
                card("Great Wall of China", "The Great Wall of China (, literally \"ten thousand li long wall\") is a "
                        + "series of fortifications in China. It was built over many centuries."));
        assertEquals("William Shakespeare (23 April 1564 – 23 April 1616) was an English dramatist, poet and theatre "
                        + "actor.",
                card("William Shakespeare", "William Shakespeare ( 23 April 1564 – 23 April 1616) was an English "
                        + "dramatist, poet and theatre actor. He wrote plays."));
        assertEquals("Kyoto is a city in Japan.", OpeningSentences.tidyBrackets("Kyoto () is a city in Japan."));
    }

    @Test
    void emptyTextGivesEmptyCard() {
        assertEquals("", card("X", "   \n"));
    }

    @Test
    void decimalNumbersAndLowercaseContinuationDoNotSplit() {
        assertEquals("Pi is about 3.14 and was known c. 1900 BC in many places around the old world.",
                card("Pi", "Pi is about 3.14 and was known c. 1900 BC in many places around the old world. Next."));
    }
}
