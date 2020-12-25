package com.epages.restdocs.apispec

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.restdocs.RestDocumentationContext
import org.springframework.restdocs.generate.RestDocumentationGenerator.ATTRIBUTE_NAME_URL_TEMPLATE
import org.springframework.restdocs.operation.Operation
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.snippet.PlaceholderResolverFactory
import org.springframework.restdocs.snippet.RestDocumentationContextPlaceholderResolverFactory
import org.springframework.restdocs.snippet.Snippet
import org.springframework.restdocs.snippet.StandardWriterResolver
import org.springframework.restdocs.templates.TemplateFormat
import org.springframework.util.PropertyPlaceholderHelper
import org.springframework.web.util.UriComponentsBuilder
import java.util.Optional

class ResourceSnippet(private val resourceSnippetParameters: ResourceSnippetParameters) : Snippet {

    private val objectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    private val propertyPlaceholderHelper = PropertyPlaceholderHelper("{", "}")

    override fun document(operation: Operation) {
        val context = operation
                .attributes[RestDocumentationContext::class.java.name] as RestDocumentationContext

        DescriptorValidator.validatePresentParameters(resourceSnippetParameters, operation)

        val placeholderResolverFactory = RestDocumentationContextPlaceholderResolverFactory()

        val model = createModel(operation, placeholderResolverFactory, context)

        (StandardWriterResolver(placeholderResolverFactory, Charsets.UTF_8.name(),
            JsonTemplateFormat
        ))
                .resolve(operation.name, "resource", context)
            .use { it.append(objectMapper.writeValueAsString(model)) }
    }

    private fun createModel(operation: Operation, placeholderResolverFactory: PlaceholderResolverFactory, context: RestDocumentationContext): ResourceModel {
        val operationId = propertyPlaceholderHelper.replacePlaceholders(operation.name, placeholderResolverFactory.create(context))

        val hasResponseBody = operation.response.contentAsString.isNotEmpty()

        val tags =
            if (resourceSnippetParameters.tags.isEmpty())
                Optional.ofNullable(getUriComponents(operation).pathSegments.firstOrNull())
                    .map { setOf(it) }
                    .orElse(emptySet())
            else resourceSnippetParameters.tags

        val requestModel = generateRequestModel(operation)

        return ResourceModel(
            operationId = operationId,
            summary = resourceSnippetParameters.summary ?: resourceSnippetParameters.description,
            description = resourceSnippetParameters.description ?: resourceSnippetParameters.summary,
            privateResource = resourceSnippetParameters.privateResource,
            deprecated = resourceSnippetParameters.deprecated,
            tags = tags,
            request = requestModel,
            response = ResponseModel(
                status = operation.response.status.value(),
                contentType = if (hasResponseBody) getContentTypeOrDefault(operation.response.headers) else null,
                headers = resourceSnippetParameters.responseHeaders.withExampleValues(operation.response.headers),
                schema = resourceSnippetParameters.responseSchema,
                responseFields = if (hasResponseBody) resourceSnippetParameters.responseFields.filter { !it.isIgnored } else emptyList(),
                example = if (hasResponseBody) operation.response.contentAsString else null
            )
        )
    }

    private fun List<HeaderDescriptorWithType>.withExampleValues(headers: HttpHeaders): List<HeaderDescriptorWithType> {
        this.map { it.withExample(headers) }
        return this
    }

    private fun HeaderDescriptorWithType.withExample(headers: HttpHeaders): HeaderDescriptorWithType {
        headers.getFirst(name)?.also { example = it }
        return this
    }

    private fun getUriComponents(operation: Operation) =
        Optional.ofNullable(operation.attributes[ATTRIBUTE_NAME_URL_TEMPLATE] as? String)
            .map { UriComponentsBuilder.fromUriString(it).build() }
            .orElseThrow { MissingUrlTemplateException() }

    private fun getUriPath(operation: Operation) =
        getUriComponents(operation).path

    private fun getContentTypeOrDefault(headers: HttpHeaders): String =
        Optional.ofNullable(headers.contentType)
            .map { MediaType(it.type, it.subtype, it.parameters) }
            .orElse(APPLICATION_JSON)
            .toString()

    private fun generateRequestModel(operation: Operation): RequestModel {
        val securityRequirements = SecurityRequirementsHandler().extractSecurityRequirements(operation)

        val path: String = getUriPath(operation)
        val method = operation.request.method.name
        val headers = resourceSnippetParameters.requestHeaders.withExampleValues(operation.request.headers)
        val pathParameters = resourceSnippetParameters.pathParameters.filter { !it.isIgnored }
        val requestParameters = resourceSnippetParameters.requestParameters.filter { !it.isIgnored }
        val hasRequestBody = operation.request.contentAsString.isNotEmpty()
        val contentType = if (hasRequestBody) getContentTypeOrDefault(operation.request.headers) else null

        verifyContentTypeWithRequestModel(contentType, resourceSnippetParameters.request)

        return when(val request = resourceSnippetParameters.request) {
            is RequestBody -> {
                RequestBodyModel(
                    path = path,
                    method = method,
                    contentType = contentType,
                    headers = headers,
                    pathParameters = pathParameters,
                    requestParameters = requestParameters,
                    schema = request.requestSchema,
                    requestFields = if (hasRequestBody) request.requestFields.filter { !it.isIgnored } else emptyList(),
                    example = if (hasRequestBody) operation.request.contentAsString else null,
                    securityRequirements = securityRequirements
                )
            }
            is MultipartRequest -> {

                val requestParts = operation.request.parts
                    .map {
                        RequestPartModel(
                            schema = request.requestPartsSchemas[it.name],
                            example = it.contentAsString,
                            partName = it.name,
                            description = request.requestParts[it.name]?.description.toString(),
                            requestFields = request.requestPartFields[it.name]!!
                        )
                    }

                MultipartRequestModel(
                    contentType = contentType,
                    path = path,
                    method = method,
                    headers = headers,
                    pathParameters = pathParameters,
                    requestParameters = requestParameters,
                    securityRequirements = securityRequirements,
                    requestParts = requestParts
                )
            }
            else -> BaseRequestModel(
                contentType = contentType,
                path = path,
                method = method,
                headers = headers,
                pathParameters = pathParameters,
                requestParameters = requestParameters,
                securityRequirements = securityRequirements
            )
        }
    }

    private fun verifyContentTypeWithRequestModel(contentType: String?, request: RequestObject?) {
        if (contentType == "multipart/form-data") {
            if (request == null) {
                throw MissingRequestModelForMultipartRequest()
            } else if (request !is MultipartRequest) {
                throw NotMatchingRequestModelForMutlipartRequest()
            }
        }
    }

    internal object JsonTemplateFormat : TemplateFormat {
        override fun getId(): String = "json"
        override fun getFileExtension(): String = "json"
    }

    private data class ResourceModel(
        val operationId: String,
        val summary: String?,
        val description: String?,
        val privateResource: Boolean,
        val deprecated: Boolean,
        val request: RequestModel,
        val response: ResponseModel,
        val tags: Set<String>
    )

    private interface RequestModel {
        val path: String
        val method: String
        val headers: List<HeaderDescriptorWithType>
        val pathParameters: List<ParameterDescriptorWithType>
        val requestParameters: List<ParameterDescriptorWithType>
        val securityRequirements: SecurityRequirements?
        val contentType: String?
    }

    private data class BaseRequestModel(
        override val path: String,
        override val method: String,
        override val headers: List<HeaderDescriptorWithType>,
        override val pathParameters: List<ParameterDescriptorWithType>,
        override val requestParameters: List<ParameterDescriptorWithType>,
        override val securityRequirements: SecurityRequirements?,
        override val contentType: String?
    ) : RequestModel

    private data class RequestBodyModel(
        val schema: Schema? = null,
        val requestFields: List<FieldDescriptor>,
        val example: String?,
        override val path: String,
        override val method: String,
        override val headers: List<HeaderDescriptorWithType>,
        override val pathParameters: List<ParameterDescriptorWithType>,
        override val requestParameters: List<ParameterDescriptorWithType>,
        override val securityRequirements: SecurityRequirements?,
        override val contentType: String?
    ) : RequestModel

    private data class MultipartRequestModel(
        val requestParts: List<RequestPartModel>,
        override val path: String,
        override val method: String,
        override val headers: List<HeaderDescriptorWithType>,
        override val pathParameters: List<ParameterDescriptorWithType>,
        override val requestParameters: List<ParameterDescriptorWithType>,
        override val securityRequirements: SecurityRequirements?,
        override val contentType: String?
    ) : RequestModel

    private data class RequestPartModel(
        val schema: Schema? = null,
        val example: String?,
        val partName: String,
        val description: String?,
        val requestFields: List<FieldDescriptor>
    )

    private data class ResponseModel(
        val status: Int,
        val contentType: String?,
        val schema: Schema? = null,
        val headers: List<HeaderDescriptorWithType>,
        val responseFields: List<FieldDescriptor>,
        val example: String?
    )

    class MissingUrlTemplateException : RuntimeException("Missing URL template - please use RestDocumentationRequestBuilders with urlTemplate to construct the request")

    class MissingRequestModelForMultipartRequest : RuntimeException("Missing request model for multipart request")

    class NotMatchingRequestModelForMutlipartRequest : RuntimeException("Not matching request model for multipart request")
}
