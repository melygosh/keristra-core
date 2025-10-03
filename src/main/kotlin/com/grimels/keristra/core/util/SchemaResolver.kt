package com.grimels.keristra.core.util

import java.io.File
import java.nio.file.Files
import org.springframework.util.ResourceUtils

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

        private fun getSchemaByPath(path: String): String {
            val file: File = ResourceUtils.getFile("classpath:schema/$path")
            val encoded = Files.readAllBytes(file.toPath())
            return String(encoded)
        }
    }

}