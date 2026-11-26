package com.linachat.android;

final class Session {
    static volatile ProtocolClient client;
    static volatile ChatUser user;

    private Session() {}
}
