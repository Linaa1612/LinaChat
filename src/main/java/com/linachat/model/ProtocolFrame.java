package com.linachat.model;

import java.util.Map;

// ProtocolFrame decrit une enveloppe logique du protocole; les trames reelles
// sont actuellement encodees sous forme de Map JSON par JsonCodec.
public record ProtocolFrame(String type, Map<String, Object> payload) {}
