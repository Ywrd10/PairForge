package com.pairforge.api.collaboration;

import com.pairforge.api.common.Language;
import java.util.UUID;

public final class DocumentMessages {
    private DocumentMessages() {}
    public record Update(UUID generationId, long sequence, UUID clientUpdateId, String content, Language language) {
        @Override public String toString() { return "Update[redacted]"; }
    }
    public record Document(String content, Language language, long version, UUID generationId) {
        @Override public String toString() { return "Document[redacted]"; }
    }
    public record Event(String type, UUID roomId, Document document, UUID clientUpdateId, String code) {}
    public static String template(Language language) {
        return language == Language.JAVA
                ? "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, PairForge!\");\n    }\n}\n"
                : "print(\"Hello, PairForge!\")\n";
    }
}
