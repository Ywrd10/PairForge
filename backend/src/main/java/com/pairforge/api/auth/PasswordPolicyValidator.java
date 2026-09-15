package com.pairforge.api.auth;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

public class PasswordPolicyValidator implements ConstraintValidator<PasswordPolicy, String> {
    @Override public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null || password.codePointCount(0, password.length()) < 15 || password.indexOf('\0') >= 0) {
            return false;
        }
        try {
            return StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password)).remaining() <= 72;
        } catch (CharacterCodingException error) {
            return false;
        }
    }
}
