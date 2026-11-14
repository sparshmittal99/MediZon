package com.medizon.service;

import com.medizon.domain.Category;
import com.medizon.domain.Medicine;
import com.medizon.domain.ServiceablePincode;
import com.medizon.domain.Store;
import com.medizon.domain.StoreArea;
import com.medizon.domain.StoreItem;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostCommitDeleteEventListener;
import org.hibernate.event.spi.PostCommitInsertEventListener;
import org.hibernate.event.spi.PostCommitUpdateEventListener;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Empties {@link CatalogCache} after a transaction that added, changed or removed a product, store, store listing,
 * delivery area, serviceable PIN code or category commits. It hooks into Hibernate itself, so every way of saving
 * these is covered without each service having to remember to. Renaming a category moves its products with a bulk
 * update Hibernate doesn't see, but the rename always saves or deletes the {@link Category} too, which is seen.
 */
@Component
@RequiredArgsConstructor
class CatalogChangeWatcher implements PostCommitInsertEventListener, PostCommitUpdateEventListener, PostCommitDeleteEventListener {

    static final Set<Class<?>> WATCHED = Set.of(
            Medicine.class, StoreItem.class, Store.class, StoreArea.class, ServiceablePincode.class, Category.class);

    private final EntityManagerFactory entityManagerFactory;
    private final CatalogCache cache;

    @PostConstruct
    void register() {
        EventListenerRegistry registry = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_COMMIT_INSERT, this);
        registry.appendListeners(EventType.POST_COMMIT_UPDATE, this);
        registry.appendListeners(EventType.POST_COMMIT_DELETE, this);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return WATCHED.contains(persister.getMappedClass());
    }

    private void changed(EntityPersister persister) {
        if (requiresPostCommitHandling(persister)) cache.clear();
    }

    @Override public void onPostInsert(PostInsertEvent event) { changed(event.getPersister()); }
    @Override public void onPostUpdate(PostUpdateEvent event) { changed(event.getPersister()); }
    @Override public void onPostDelete(PostDeleteEvent event) { changed(event.getPersister()); }

    // Nothing was committed, so nothing changed
    @Override public void onPostInsertCommitFailed(PostInsertEvent event) {}
    @Override public void onPostUpdateCommitFailed(PostUpdateEvent event) {}
    @Override public void onPostDeleteCommitFailed(PostDeleteEvent event) {}
}
