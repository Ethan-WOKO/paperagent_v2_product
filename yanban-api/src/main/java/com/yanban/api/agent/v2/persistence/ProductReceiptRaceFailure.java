package com.yanban.api.agent.v2.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

final class ProductReceiptRaceFailure {
    private ProductReceiptRaceFailure() {
    }

    static boolean recognized(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException
                    || current instanceof ConstraintViolationException
                    || current instanceof CannotAcquireLockException
                    || current instanceof CannotSerializeTransactionException
                    || current instanceof PessimisticLockingFailureException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
