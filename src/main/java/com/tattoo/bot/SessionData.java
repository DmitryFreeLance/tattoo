package com.tattoo.bot;

public record SessionData(long userId, ConversationState state, String pendingPhotoFileId) {
}
