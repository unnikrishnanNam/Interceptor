package com.proxy.interceptor.dto;

import com.proxy.interceptor.model.User;

public record LoginResult(boolean success, String token, User user, String error) {}
