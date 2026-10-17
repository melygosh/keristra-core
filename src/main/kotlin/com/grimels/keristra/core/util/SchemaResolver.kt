package com.grimels.keristra.core.util


class SchemaResolver {

    companion object {
        public fun getSchema(context: String): String {
            if (context == "bmi") {
                return getSchemaByPath("bmi-schema.xml")
            }
            if (context == "hotels") {
                return getSchemaByPath("hotel-schema.xml")
            }
            if (context == "vap") {
                return getSchemaByPath("vap-schema.xml")
            }
            if (context == "education") {
                return getSchemaByPath("education-schema.json")
            }
            if (context == "mortgage") {
                return getSchemaByPath("mortgage-schema.json")
            }

            return getSchemaByPath("user-schema.xml")
        }

        // Classpath stream instead of File, so schemas also load from the packaged jar.
        private fun getSchemaByPath(path: String): String {
            val encoded = SchemaResolver::class.java.getResourceAsStream("/schema/$path")?.use { it.readBytes() }
                ?: throw java.io.FileNotFoundException("classpath:schema/$path")
            return String(encoded)
        }
    }

}