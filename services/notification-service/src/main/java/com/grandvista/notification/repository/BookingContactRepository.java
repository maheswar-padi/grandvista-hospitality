package com.grandvista.notification.repository;

import com.grandvista.notification.domain.BookingContact;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingContactRepository extends JpaRepository<BookingContact, UUID> {
}
