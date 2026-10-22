package com.grandvista.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mailFrom    the From address of every e-mail
 * @param webBaseUrl  public URL of the web application, used for links in e-mails
 * @param hotelName   name shown in e-mails
 */
@ConfigurationProperties("gv.notification")
public record NotificationProperties(String mailFrom, String webBaseUrl, String hotelName) {

    public NotificationProperties {
        mailFrom = mailFrom == null ? "GrandVista Hospitality <reservations@grandvista.local>" : mailFrom;
        webBaseUrl = webBaseUrl == null ? "http://localhost:3000" : webBaseUrl.replaceAll("/+$", "");
        hotelName = hotelName == null ? "GrandVista Hospitality" : hotelName;
    }
}
