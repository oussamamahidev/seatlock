package com.seatlock.inventory.application;

import com.seatlock.inventory.api.EventView;
import com.seatlock.inventory.api.PageResponse;
import com.seatlock.inventory.api.SeatView;
import com.seatlock.inventory.persistence.EventRepository;
import com.seatlock.inventory.persistence.SeatRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InventoryQueryService {
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;

    public InventoryQueryService(EventRepository eventRepository, SeatRepository seatRepository) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<EventView> events(int page, int size) {
        validatePage(page, size);
        var sort = Sort.by(Sort.Order.asc("startsAt"), Sort.Order.asc("id"));
        return PageResponse.from(eventRepository.findAll(PageRequest.of(page, size, sort))
                .map(EventView::from));
    }

    @Transactional(readOnly = true)
    public PageResponse<SeatView> seats(UUID eventId, int page, int size) {
        validatePage(page, size);
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        var sort = Sort.by(Sort.Order.asc("label"), Sort.Order.asc("id"));
        return PageResponse.from(seatRepository.findByEvent_Id(eventId, PageRequest.of(page, size, sort))
                .map(SeatView::from));
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new InvalidPageException();
        }
    }
}
