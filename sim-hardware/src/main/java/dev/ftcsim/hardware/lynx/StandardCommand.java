package dev.ftcsim.hardware.lynx;

/** The commands every REV module understands whatever its interfaces; their numbers are fixed by the protocol. */
public enum StandardCommand {
    ACK(0x7F01),
    NACK(0x7F02),
    GET_MODULE_STATUS(0x7F03),
    KEEP_ALIVE(0x7F04),
    FAIL_SAFE(0x7F05),
    SET_NEW_MODULE_ADDRESS(0x7F06),
    QUERY_INTERFACE(0x7F07),
    START_DOWNLOAD(0x7F08),
    DOWNLOAD_CHUNK(0x7F09),
    SET_MODULE_LED_COLOR(0x7F0A),
    GET_MODULE_LED_COLOR(0x7F0B),
    SET_MODULE_LED_PATTERN(0x7F0C),
    GET_MODULE_LED_PATTERN(0x7F0D),
    DEBUG_LOG_LEVEL(0x7F0E),
    DISCOVERY(0x7F0F);

    private final int number;

    StandardCommand(int number) {
        this.number = number;
    }

    public int number() {
        return number;
    }
}
