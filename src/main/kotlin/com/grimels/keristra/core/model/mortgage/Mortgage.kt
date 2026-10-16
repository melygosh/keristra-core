package com.grimels.keristra.core.model.mortgage

open class Mortgage {
    var application: Application

    constructor(application: Application) {
        this.application = application
    }
}

class Application(
    var id: Int,
    var purpose: String,
    var creditScore: Int,
    var horizonYears: Int,
    var incomeStability: String,
    var rateSensitivity: String,
    var fixedPreference: String,
    var loan: Amount,
    var collateral: Collateral,
    var cashOutAmount: Double? = null,
    var income: Amount,
    var housing: Housing,
    var debts: List<Debt>? = null,
    var dti: Double? = null,
    var ltv: Double? = null,
)

class Amount (
    var amount: Double,
)

class Collateral (
    var purchasePrice: Double? = null,
    var appraisedValue: Double,
)

class Housing (
    var principalInterest: Double,
    var taxes: Double,
    var insurance: Double,
    var hoa: Double? = 0.0,
    var mip: Double? = 0.0,
)

class Debt (
    var kind: String,
    var monthlyPayment: Double,
    var include: Boolean,
)