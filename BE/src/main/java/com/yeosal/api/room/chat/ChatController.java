package com.yeosal.api.room.chat;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms")
public class ChatController {

    private final ChatService chatService;
    private final CurrentUser currentUser;

    public ChatController(ChatService chatService, CurrentUser currentUser) {
        this.chatService = chatService;
        this.currentUser = currentUser;
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<ChatService.MessagePage> list(
            Authentication auth,
            @PathVariable long id,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false, defaultValue = "30") int limit
    ) {
        User me = currentUser.require(auth);
        return ApiResponse.of(chatService.list(me, id, cursor, limit));
    }

    @PostMapping("/{id}/messages")
    public ApiResponse<ChatService.MessageDto> send(
            Authentication auth,
            @PathVariable long id,
            @Valid @RequestBody SendRequest body
    ) {
        User me = currentUser.require(auth);
        return ApiResponse.of(chatService.sendUserMessage(me, id, body.body()));
    }

    public record SendRequest(@NotBlank @Size(max = 2000) String body) {}
}
