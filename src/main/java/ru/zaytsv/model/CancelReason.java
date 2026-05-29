package ru.zaytsv.model;

/**
 * Причина аннулирования чека. ФНС принимает строго определённый текст комментария.
 */
public enum CancelReason {

    /** «Чек сформирован ошибочно». */
    ERROR("Чек сформирован ошибочно"),

    /** «Возврат средств». */
    REFUND("Возврат средств");

    private final String comment;

    CancelReason(String comment) {
        this.comment = comment;
    }

    /** Текст комментария, отправляемый в поле {@code comment} запроса {@code /cancel}. */
    public String getComment() {
        return comment;
    }
}
