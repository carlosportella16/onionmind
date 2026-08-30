package com.onionmind.content;

public record ProcessingResult(Document document, Status status, String error) {
    /**
     * SKIPPED/FAILED let the pipeline carry on with the previous document state. HALT stops
     * the pipeline immediately — used by the illegal-content guard, which must prevent any
     * further processing (and persistence) of the page.
     */
    public enum Status { SUCCESS, SKIPPED, FAILED, HALT }

    public static ProcessingResult success(Document doc) { return new ProcessingResult(doc, Status.SUCCESS, null); }
    public static ProcessingResult unchanged(Document doc) { return new ProcessingResult(doc, Status.SKIPPED, null); }
    public static ProcessingResult skipped(Document doc, String reason) { return new ProcessingResult(doc, Status.SKIPPED, reason); }
    public static ProcessingResult failed(Document doc, String error) { return new ProcessingResult(doc, Status.FAILED, error); }
    public static ProcessingResult halt(Document doc, String reason) { return new ProcessingResult(doc, Status.HALT, reason); }
}
