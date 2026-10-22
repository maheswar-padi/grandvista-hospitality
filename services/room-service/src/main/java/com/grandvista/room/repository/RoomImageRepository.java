package com.grandvista.room.repository;

import com.grandvista.room.domain.RoomImage;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoomImageRepository extends JpaRepository<RoomImage, UUID> {

    /** One query for the images of a whole page of rooms. */
    @Query("select i.room.id as roomId, i as image from RoomImage i where i.room.id in :roomIds order by i.sortOrder, i.createdAt")
    List<RoomImageRow> findForRooms(Collection<UUID> roomIds);

    interface RoomImageRow {
        UUID getRoomId();

        RoomImage getImage();
    }
}
