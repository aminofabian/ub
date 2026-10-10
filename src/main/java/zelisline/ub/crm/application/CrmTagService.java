package zelisline.ub.crm.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.domain.CrmTag;
import zelisline.ub.crm.repository.CrmTagRepository;

/** Per-business tag vocabulary for the inbox. */
@Service
@RequiredArgsConstructor
public class CrmTagService {

    private final CrmTagRepository tagRepository;

    @Transactional(readOnly = true)
    public List<CrmDtos.TagRow> list(String businessId) {
        return tagRepository.findByBusinessIdOrderByNameAsc(businessId).stream()
                .map(CrmTagService::toRow)
                .toList();
    }

    @Transactional
    public CrmDtos.TagRow create(String businessId, String name, String color) {
        String normalised = name.trim();
        if (tagRepository.existsByBusinessIdAndName(businessId, normalised)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tag already exists");
        }
        CrmTag tag = new CrmTag();
        tag.setBusinessId(businessId);
        tag.setName(normalised);
        tag.setColor(blankToNull(color));
        return toRow(tagRepository.save(tag));
    }

    @Transactional
    public void delete(String businessId, String id) {
        CrmTag tag = tagRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found"));
        tagRepository.delete(tag);
    }

    private static CrmDtos.TagRow toRow(CrmTag tag) {
        return new CrmDtos.TagRow(tag.getId(), tag.getName(), tag.getColor(), tag.getCreatedAt());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
