package dev.victorhleme.multitenancy.exceptions;

import java.text.MessageFormat;

public class NotFoundException extends RuntimeException {
    public NotFoundException(Class<?> clazz, Object id) {
        super(MessageFormat.format("{0} with identifier {1} not found", clazz.getSimpleName(), id.toString()));
    }
}
