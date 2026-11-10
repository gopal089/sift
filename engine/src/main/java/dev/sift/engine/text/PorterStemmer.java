package dev.sift.engine.text;

/**
 * The Porter stemming algorithm (M. F. Porter, 1980), written by hand.
 *
 * <p>It removes common English suffixes so that related words share one "stem":
 * {@code connect, connected, connecting, connection, connections -> connect}.
 * Stems do not have to be real words ({@code happy -> happi}); they only need to be
 * the same for words that belong together.
 *
 * <p>This follows Martin Porter's reference implementation (including its two small,
 * documented departures from the 1980 paper: {@code bli -> ble} and {@code logi -> log}),
 * so its output matches his official 23,531-word test list exactly.
 *
 * <p>Input must be lowercase {@code a-z}. Words of length 1 or 2 are returned unchanged.
 *
 * <h2>Key idea: the measure m</h2>
 * Write a word as consonant groups (C) and vowel groups (V): {@code [C](VC)^m[V]}.
 * <b>m</b> counts the VC pairs. {@code tree} = C V → m=0; {@code trouble} = C V C V → m=1;
 * {@code private} = C V C V C V → m=2. Most rules only fire when the part that is left
 * has m &gt; 0 (or &gt; 1), which stops short words from being chopped to nothing.
 */
public final class PorterStemmer {

    private char[] b;   // the word being stemmed
    private int k;      // index of the last letter of the current word (end shrinks as we stem)
    private int j;      // index of the last letter of the stem *before* a suffix we matched

    private PorterStemmer(String word) {
        this.b = word.toCharArray();
        this.k = b.length - 1;
    }

    /** Returns the stem of a lowercase a-z word. Thread-safe (each call uses its own state). */
    public static String stem(String word) {
        if (word.length() <= 2) {
            return word;
        }
        PorterStemmer s = new PorterStemmer(word);
        s.step1ab();
        s.step1c();
        s.step2();
        s.step3();
        s.step4();
        s.step5();
        return new String(s.b, 0, s.k + 1);
    }

    // ------------------------------------------------------------------ helpers

    /** True if b[i] is a consonant. 'y' is a consonant at the start or after a vowel ("toy"), else a vowel ("syzygy"). */
    private boolean isConsonant(int i) {
        switch (b[i]) {
            case 'a', 'e', 'i', 'o', 'u':
                return false;
            case 'y':
                return i == 0 || !isConsonant(i - 1);
            default:
                return true;
        }
    }

    /** The measure m of b[0..j]: the number of vowel-group → consonant-group switches. */
    private int measure() {
        int n = 0;
        int i = 0;
        // skip the leading consonants [C]
        while (true) {
            if (i > j) return n;
            if (!isConsonant(i)) break;
            i++;
        }
        i++;
        while (true) {
            // skip a vowel group
            while (true) {
                if (i > j) return n;
                if (isConsonant(i)) break;
                i++;
            }
            i++;
            n++; // finished one VC pair
            // skip a consonant group
            while (true) {
                if (i > j) return n;
                if (!isConsonant(i)) break;
                i++;
            }
            i++;
        }
    }

    /** True if b[0..j] contains a vowel. */
    private boolean vowelInStem() {
        for (int i = 0; i <= j; i++) {
            if (!isConsonant(i)) return true;
        }
        return false;
    }

    /** True if b[i-1..i] is a double consonant, like "tt" or "ss". */
    private boolean doubleConsonant(int i) {
        return i >= 1 && b[i] == b[i - 1] && isConsonant(i);
    }

    /**
     * True if b[i-2..i] is consonant-vowel-consonant and the last consonant is not w, x or y.
     * Used to restore an 'e' on short words: "hop(e)" → "hope", but not "snow".
     */
    private boolean cvc(int i) {
        if (i < 2 || !isConsonant(i) || isConsonant(i - 1) || !isConsonant(i - 2)) return false;
        char ch = b[i];
        return ch != 'w' && ch != 'x' && ch != 'y';
    }

    /** True if the word (up to k) ends with s. If so, sets j to the index just before the suffix. */
    private boolean endsWith(String s) {
        int len = s.length();
        int start = k - len + 1;
        if (start < 0) return false;
        for (int i = 0; i < len; i++) {
            if (b[start + i] != s.charAt(i)) return false;
        }
        j = k - len;
        return true;
    }

    /** Replaces the suffix after j with s, and moves k to the new end. */
    private void setTo(String s) {
        int len = s.length();
        if (j + 1 + len > b.length) {
            b = java.util.Arrays.copyOf(b, j + 1 + len); // a replacement can be longer ("bl" → "ble")
        }
        for (int i = 0; i < len; i++) {
            b[j + 1 + i] = s.charAt(i);
        }
        k = j + len;
    }

    /** setTo(s) only if the stem before the suffix has m &gt; 0. */
    private void replaceIfMeasurePositive(String s) {
        if (measure() > 0) setTo(s);
    }

    // ------------------------------------------------------------------ the steps

    /**
     * Step 1a: plurals.   caresses → caress, ponies → poni, cats → cat (but caress stays).
     * Step 1b: -ed / -ing. agreed → agree, plastered → plaster, motoring → motor,
     *          then tidy up: conflat(ed) → conflate, hopp(ing) → hop, fil(ing) → file.
     */
    private void step1ab() {
        if (b[k] == 's') {
            if (endsWith("sses")) {
                k -= 2;
            } else if (endsWith("ies")) {
                setTo("i");
            } else if (b[k - 1] != 's') {
                k--;
            }
        }
        if (endsWith("eed")) {
            if (measure() > 0) k--;
        } else if ((endsWith("ed") || endsWith("ing")) && vowelInStem()) {
            k = j;
            if (endsWith("at")) {
                setTo("ate");
            } else if (endsWith("bl")) {
                setTo("ble");
            } else if (endsWith("iz")) {
                setTo("ize");
            } else if (doubleConsonant(k)) {
                k--;
                char ch = b[k];
                if (ch == 'l' || ch == 's' || ch == 'z') k++; // keep "fall", "hiss", "fizz"
            } else {
                j = k;
                if (measure() == 1 && cvc(k)) {
                    j = k;
                    setTo("e");
                }
            }
        }
    }

    /** Step 1c: final y → i when there is another vowel in the stem.  happy → happi, sky stays. */
    private void step1c() {
        if (endsWith("y") && vowelInStem()) {
            b[k] = 'i';
        }
    }

    /** Step 2: double suffixes → single ones (when m &gt; 0).  relational → relate, digitizer → digitize. */
    private void step2() {
        if (k == 0) return;
        switch (b[k - 1]) {
            case 'a' -> {
                if (endsWith("ational")) replaceIfMeasurePositive("ate");
                else if (endsWith("tional")) replaceIfMeasurePositive("tion");
            }
            case 'c' -> {
                if (endsWith("enci")) replaceIfMeasurePositive("ence");
                else if (endsWith("anci")) replaceIfMeasurePositive("ance");
            }
            case 'e' -> {
                if (endsWith("izer")) replaceIfMeasurePositive("ize");
            }
            case 'l' -> {
                if (endsWith("bli")) replaceIfMeasurePositive("ble");
                else if (endsWith("alli")) replaceIfMeasurePositive("al");
                else if (endsWith("entli")) replaceIfMeasurePositive("ent");
                else if (endsWith("eli")) replaceIfMeasurePositive("e");
                else if (endsWith("ousli")) replaceIfMeasurePositive("ous");
            }
            case 'o' -> {
                if (endsWith("ization")) replaceIfMeasurePositive("ize");
                else if (endsWith("ation")) replaceIfMeasurePositive("ate");
                else if (endsWith("ator")) replaceIfMeasurePositive("ate");
            }
            case 's' -> {
                if (endsWith("alism")) replaceIfMeasurePositive("al");
                else if (endsWith("iveness")) replaceIfMeasurePositive("ive");
                else if (endsWith("fulness")) replaceIfMeasurePositive("ful");
                else if (endsWith("ousness")) replaceIfMeasurePositive("ous");
            }
            case 't' -> {
                if (endsWith("aliti")) replaceIfMeasurePositive("al");
                else if (endsWith("iviti")) replaceIfMeasurePositive("ive");
                else if (endsWith("biliti")) replaceIfMeasurePositive("ble");
            }
            case 'g' -> {
                if (endsWith("logi")) replaceIfMeasurePositive("log");
            }
            default -> { }
        }
    }

    /** Step 3: -ic-, -full, -ness etc. (when m &gt; 0).  triplicate → triplic, hopeful → hope, goodness → good. */
    private void step3() {
        switch (b[k]) {
            case 'e' -> {
                if (endsWith("icate")) replaceIfMeasurePositive("ic");
                else if (endsWith("ative")) replaceIfMeasurePositive("");
                else if (endsWith("alize")) replaceIfMeasurePositive("al");
            }
            case 'i' -> {
                if (endsWith("iciti")) replaceIfMeasurePositive("ic");
            }
            case 'l' -> {
                if (endsWith("ical")) replaceIfMeasurePositive("ic");
                else if (endsWith("ful")) replaceIfMeasurePositive("");
            }
            case 's' -> {
                if (endsWith("ness")) replaceIfMeasurePositive("");
            }
            default -> { }
        }
    }

    /** Step 4: remove -ant, -ence, -ment, ... when the stem has m &gt; 1.  revival → reviv, adjustment → adjust. */
    private void step4() {
        if (k == 0) return;
        boolean matched = switch (b[k - 1]) {
            case 'a' -> endsWith("al");
            case 'c' -> endsWith("ance") || endsWith("ence");
            case 'e' -> endsWith("er");
            case 'i' -> endsWith("ic");
            case 'l' -> endsWith("able") || endsWith("ible");
            case 'n' -> endsWith("ant") || endsWith("ement") || endsWith("ment") || endsWith("ent");
            // -ion is only removed after s or t: adoption → adopt, but not "lion"
            case 'o' -> (endsWith("ion") && j >= 0 && (b[j] == 's' || b[j] == 't')) || endsWith("ou");
            case 's' -> endsWith("ism");
            case 't' -> endsWith("ate") || endsWith("iti");
            case 'u' -> endsWith("ous");
            case 'v' -> endsWith("ive");
            case 'z' -> endsWith("ize");
            default -> false;
        };
        if (matched && measure() > 1) {
            k = j;
        }
    }

    /**
     * Step 5a: remove a final -e if m &gt; 1, or if m = 1 and the stem is not CVC.  probate → probat, rate stays.
     * Step 5b: -ll → -l if m &gt; 1.  controll → control, roll stays.
     */
    private void step5() {
        j = k;
        if (b[k] == 'e') {
            int m = measure();
            if (m > 1 || (m == 1 && !cvc(k - 1))) k--;
        }
        if (b[k] == 'l' && doubleConsonant(k) && measure() > 1) k--;
    }
}
