package com.grimels.keristra.core.model.mortgage

data class Mortgage(val application: Application)

data class Application(
    val id: Int,
    val purpose: String,
    val creditScore: Int,
    val horizonYears: Int,
    val incomeStability: String,
    val rateSensitivity: String,
    val fixedPreference: String,
    val loan: Amount,
    val collateral: Collateral,
    val cashOutAmount: Double? = null,
    val income: Amount,
    val housing: Housing,
    val debts: List<Debt>? = null,
    val dti: Double? = null,
    val ltv: Double? = null,
)

data class Amount (
    val amount: Double,
)

data class Collateral (
    val purchasePrice: Double? = null,
    val appraisedValue: Double,
)

data class Housing (
    val principalInterest: Double,
    val taxes: Double,
    val insurance: Double,
    val hoa: Double? = 0.0,
    val mip: Double? = 0.0,
)

data class Debt (
    val kind: String,
    val monthlyPayment: Double,
    val include: Boolean,
)