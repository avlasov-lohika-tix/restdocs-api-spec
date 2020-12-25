package com.epages.restdocs.apispec.jsonschema

import com.epages.restdocs.apispec.model.FieldDescriptor
import com.epages.restdocs.apispec.model.MultipartRequestModel
import org.everit.json.schema.ObjectSchema
import java.util.*

class JsonSchemaFromMultipartsGenerator {

    fun generate(multipartRequestModel: MultipartRequestModel): String {
        val jsonFieldPaths = multipartRequestModel.requestParts
            .flatMap { it.requestFields }
            .let { reduceFieldDescriptors(it) }
            .map { JsonFieldPath.compile(it) }

        val schema = traverse(Collections.emptyList(), jsonFieldPaths, ObjectSchema.builder() as ObjectSchema.Builder)

        return toFormattedString(unWrapRootArray(jsonFieldPaths, schema))
    }

}