package com.yanban.api.attachment;

import jakarta.persistence.*;

/** Immutable attachment selection for a durable turn or Plan, including an empty selection. */
@Entity
@Table(name="agent_attachment_snapshots")
public class AttachmentSnapshot {
    @Id @Column(length=512) String id;
    @Column(nullable=false) Long sessionId;
    @Column(nullable=false,length=256) String attachmentIds;
    protected AttachmentSnapshot() {}
    AttachmentSnapshot(String id, Long sessionId, String attachmentIds) {
        this.id=id; this.sessionId=sessionId; this.attachmentIds=attachmentIds;
    }
}
