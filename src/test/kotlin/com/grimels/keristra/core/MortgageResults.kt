package com.grimels.keristra.core

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.grimels.keristra.core.model.mortgage.Application
import com.grimels.keristra.core.model.mortgage.Mortgage

class MortgageResult: Mortgage {

    var description: String
    var expected: String?

    @JsonCreator constructor(application: Application, description: String, expected: String?) : super(application) {
        this.description = description
        this.expected = expected
    }

}
class MortgageGeneratedResult(
    @JsonProperty("description")
    var description: String,
    @JsonProperty("expected")
    var expected: Any?,
    @JsonProperty("object")
    var objectEntity: Mortgage,
) {
}