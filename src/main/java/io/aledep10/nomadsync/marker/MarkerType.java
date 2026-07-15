package io.aledep10.nomadsync.marker;

public enum MarkerType {
    VAULT(".nomadsync-vault"),
    WORKSPACE(".nomadsync-workspace"),
    CONFIG(".nomadsync-config"),
    CATALOG(".nomadsync-catalog"),
    BACKUPS(".nomadsync-backups"),
    CONFLICTS(".nomadsync-conflicts");

    private final String folderName;
    MarkerType(String folderName) { this.folderName = folderName; }
    public String folderName() { return folderName; }

    // Filename of the JSON descriptor inside every reserved folder, regardless
    // of MarkerType — the folder name already disambiguates the kind, so the
    // file inside doesn't need to repeat it.
    public static final String DESCRIPTOR_FILE_NAME = "descriptor.json";
}