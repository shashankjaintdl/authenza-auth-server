package com.authenza.notification.service;

import com.authenza.common.dto.NotificationRequest;

public interface NotificationService {

    void sendNotification(NotificationRequest request);
    
}
