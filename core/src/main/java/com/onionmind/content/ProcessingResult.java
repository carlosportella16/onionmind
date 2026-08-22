package com.onionmind.content;

public record ProcessingResult(Document document, Status status, String error) {
    public enum Status { SUCCESS, SKIPPED, FAILED }

    public static ProcessingResult success(Document doc) { return new ProcessingResult(doc, Status.SUCCESS, null); }
    public static ProcessingResult unchanged(Document doc) { return new ProcessingResult(doc, Status.SKIPPED, null); }
    public static ProcessingResult skipped(Document doc, String reason) { return new ProcessingResult(doc, Status.SKIPPED, reason); }
    public static ProcessingResult failed(Document doc, String error) { return new ProcessingResult(doc, Status.FAILED, error); }
}
