package zelisline.ub.crm.application;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.domain.CrmQuickReply;
import zelisline.ub.crm.repository.CrmQuickReplyRepository;

/** Saved reply snippets for the inbox composer. */
@Service
@RequiredArgsConstructor
public class CrmQuickReplyService {

    private final CrmQuickReplyRepository quickReplyRepository;

    @Transactional(readOnly = true)
    public List<CrmDtos.QuickReplyRow> list(String businessId) {
        return quickReplyRepository.findByBusinessIdOrderByShortcutAsc(businessId).stream()
                .map(CrmQuickReplyService::toRow)
                .toList();
    }

    @Transactional
    public CrmDtos.QuickReplyRow create(String businessId, String shortcut, String body) {
        String normalised = shortcut.trim();
        if (quickReplyRepository.existsByBusinessIdAndShortcut(businessId, normalised)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shortcut already exists");
        }
        CrmQuickReply quickReply = new CrmQuickReply();
        quickReply.setBusinessId(businessId);
        quickReply.setShortcut(normalised);
        quickReply.setBody(body);
        return toRow(quickReplyRepository.save(quickReply));
    }

    @Transactional
    public void delete(String businessId, String id) {
        CrmQuickReply quickReply = quickReplyRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quick reply not found"));
        quickReplyRepository.delete(quickReply);
    }

    private static CrmDtos.QuickReplyRow toRow(CrmQuickReply qr) {
        return new CrmDtos.QuickReplyRow(qr.getId(), qr.getShortcut(), qr.getBody(), qr.getCreatedAt());
    }
}
