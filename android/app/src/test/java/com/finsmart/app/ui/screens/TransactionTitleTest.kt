package com.finsmart.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionTitleTest {
    @Test fun merchantInsideCardDescription() {
        assertEquals("MAGNIT", transactionTitle("Операция по карте: ****1234, место совершения операции: RU/Ekaterinburg/MAGNIT, MCC: 5411"))
        assertEquals("Аптека", transactionTitle("Операция по карте: ****1234; торговая точка: Аптека; mcc: 5912"))
        assertEquals("Оплата картой", transactionTitle("Операция по карте: ****1234, MCC: 5411"))
    }
}
