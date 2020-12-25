package com.epages.restdocs.apispec.jsonschema

import com.epages.restdocs.apispec.model.FieldDescriptor
import org.everit.json.schema.ObjectSchema
import java.util.Collections.emptyList

class JsonSchemaFromFieldDescriptorsGenerator {

    fun generateSchema(fieldDescriptors: List<FieldDescriptor>, title: String? = null): String {
        val jsonFieldPaths = reduceFieldDescriptors(fieldDescriptors)
            .map { JsonFieldPath.compile(it) }

        val schema = traverse(emptyList(), jsonFieldPaths, ObjectSchema.builder().title(title) as ObjectSchema.Builder)

        return toFormattedString(unWrapRootArray(jsonFieldPaths, schema))
    }

}
