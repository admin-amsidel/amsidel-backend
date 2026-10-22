package com.amsidel.backend.security;

import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.service.MenuAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Refuses (403) an API marked with {@link Checkpoint} when the signed-in person has none of its menus, so a menu
 * hidden in the portal cannot be used by calling the API directly. Runs after Spring Security has signed them in.
 */
@Component
@RequiredArgsConstructor
public class CheckpointInterceptor implements HandlerInterceptor {

    private final MenuAccessService menuAccess;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Checkpoint checkpoint = method.getMethodAnnotation(Checkpoint.class);
        if (checkpoint == null) {
            checkpoint = method.getBeanType().getAnnotation(Checkpoint.class);
        }
        if (checkpoint == null) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser me) || !menuAccess.can(me, checkpoint.value())) {
            throw ApiException.forbidden("You do not have access to this menu. Ask your admin.");
        }
        return true;
    }
}
