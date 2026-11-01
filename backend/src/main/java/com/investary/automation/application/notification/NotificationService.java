package com.investary.automation.application.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.investary.automation.application.AutomationExceptions.NotificationNotFoundException;
import com.investary.automation.domain.NotificationEntity;
import com.investary.automation.domain.NotificationPriority;
import com.investary.automation.domain.NotificationType;
import com.investary.automation.persistence.NotificationStore;
import com.investary.automation.persistence.NotificationStore.NewRow;
import com.investary.automation.persistence.NotificationStore.Row;
import com.investary.common.web.PageResponse;

/**
 * Stores and serves in-app notifications. Creating one never fails the caller: a database error is logged
 * (ids and type only, never the text) and the notification is dropped.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationStore store;
    private final Clock clock;

    public NotificationService(NotificationStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** @return true if a new notification was stored (false for a duplicate key or a failure) */
    public boolean notify(NewRow row) {
        try {
            boolean created = store.insert(row, clock.instant());
            if (created) {
                log.info("Notification created type={} entity={} entityId={}", row.type(), row.entityType(), row.entityId());
            }
            return created;
        } catch (RuntimeException e) {
            log.warn("Notification could not be stored type={} entityId={}: {}", row.type(), row.entityId(), e.toString());
            return false;
        }
    }

    public void notifyAll(List<NewRow> rows) {
        rows.forEach(this::notify);
    }

    public void notify(Optional<NewRow> row) {
        row.ifPresent(this::notify);
    }

    public PageResponse<NotificationView> list(boolean unreadOnly, NotificationType type, int page, int size) {
        long total = store.count(unreadOnly, type);
        List<NotificationView> content = store.list(unreadOnly, type, page, size).stream().map(NotificationView::of).toList();
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    public long unreadCount() {
        return store.count(true, null);
    }

    public NotificationView markRead(UUID id, boolean read) {
        if (!store.setRead(id, read ? clock.instant() : null)) {
            throw new NotificationNotFoundException(id);
        }
        return store.find(id).map(NotificationView::of).orElseThrow(() -> new NotificationNotFoundException(id));
    }

    public int markAllRead() {
        return store.markAllRead(clock.instant());
    }

    /**
     * @param entityType what the notification links to (email, document, brief, assessment, job, ...)
     */
    public record NotificationView(UUID id, NotificationType type, NotificationPriority priority, String title, String message,
                                   NotificationEntity entityType, UUID entityId, UUID portfolioId, Instant createdAt, Instant readAt,
                                   boolean read) {

        static NotificationView of(Row row) {
            return new NotificationView(row.id(), row.type(), row.priority(), row.title(), row.message(), row.entityType(), row.entityId(),
                    row.portfolioId(), row.createdAt(), row.readAt(), row.readAt() != null);
        }
    }
}
