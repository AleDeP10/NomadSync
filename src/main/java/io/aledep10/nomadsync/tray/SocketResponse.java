package io.aledep10.nomadsync.tray;

public enum SocketResponse {
    ACK,        // message received and queued successfully
    NACK,       // message received but rejected (e.g. unknown event type)
    ERROR       // server encountered an unexpected error
}
