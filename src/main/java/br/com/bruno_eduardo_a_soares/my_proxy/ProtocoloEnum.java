package br.com.bruno_eduardo_a_soares.my_proxy;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

@SuppressWarnings("HttpUrlsUsage")
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@Getter
public enum ProtocoloEnum {
    HTTP("http://"),
    HTTPS("https://");

    private final String name;

}
