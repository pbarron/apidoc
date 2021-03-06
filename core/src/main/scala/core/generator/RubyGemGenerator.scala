package core.generator

import core._
import Text._
import scala.collection.mutable.ListBuffer

object RubyGemGenerator {

  def apply(json: String): String = {
    generate(ServiceDescription(json))
  }

  def generate(sd: ServiceDescription): String = {
    new RubyGemGenerator(sd).generate
  }

  def generateEnumClass(modelName: String, fieldName: String, et: EnumerationFieldType): String = {
    val className = Text.underscoreToInitCap(fieldName)
    val lines = ListBuffer[String]()
    lines.append(s"class $className")

    lines.append("")
    lines.append("  attr_reader :value")

    lines.append("")
    lines.append("  def initialize(value)")
    lines.append("    @value = HttpClient::Preconditions.assert_class('value', value, String)")
    lines.append("  end")

    lines.append("")
    lines.append(s"  # Returns the instance of ${className} for this value, creating a new instance for an unknown value")
    lines.append(s"  def $className.apply(value)")
    lines.append(s"    if value.instance_of?($className)")
    lines.append(s"      value")
    lines.append(s"    else")
    lines.append(s"      HttpClient::Preconditions.assert_class_or_nil('value', value, String)")
    lines.append(s"      value.nil? ? nil : (from_string(value) || $className.new(value))")
    lines.append(s"    end")
    lines.append(s"  end")
    lines.append("")
    lines.append(s"  # Returns the instance of $className for this value, or nil if not found")
    lines.append(s"  def $className.from_string(value)")
    lines.append("    HttpClient::Preconditions.assert_class('value', value, String)")
    lines.append(s"    $className.all.find { |v| v.value == value }")
    lines.append("  end")

    // .all needs to come first. In the event there is an enum value
    // named 'all' - user will lose the 'all' method but their enum
    // value will be preserved. This is subpar, but an edge case and
    // good enough for now.
    lines.append("")
    lines.append(s"  def $className.all")
    lines.append("    @@all ||= [" + et.values.map(v => s"$className.${Text.initLowerCase(Text.camelCaseToUnderscore(v))}").mkString(", ") + "]")
    lines.append("  end")

    lines.append("")
    et.values.foreach { value =>
      val varName = Text.initLowerCase(Text.camelCaseToUnderscore(value))
      lines.append(s"  def $className.$varName")
      lines.append(s"    @@_$varName ||= $className.new('$value')")
      lines.append("  end")
      lines.append("")
    }

    lines.append("end")
    val result = lines.mkString("\n")
    println(result)
    result
  }

}

/**
 * Generates a Ruby Gem file based on the service description
 * from api.json
 */
case class RubyGemGenerator(service: ServiceDescription) {

  private val moduleName = Text.safeName(service.name)

  def generate(): String = {
    RubyHttpClient.require +
    "\n\n" +
    service.description.map { desc => GeneratorUtil.formatComment(desc) + "\n" }.getOrElse("") +
    s"module ${moduleName}\n" +
    generateClient() +
    "\n\n  module Models\n" +
    service.models.map { generateModel(_) }.mkString("\n\n") +
    "\n\n  end" +
    "\n\n  module Clients\n\n" +
    service.resources.map { res => generateClientForResource(res) }.mkString("\n\n") +
    "\n\n  end\n\n  # ===== END OF SERVICE DEFINITION =====\n  " +
    RubyHttpClient.contents +
    "\nend"
  }

  private def generateClient(): String = {
    val sb = ListBuffer[String]()
    val url = service.baseUrl

    sb.append(s"""
  class Client

    def initialize(url, opts={})
      @url = HttpClient::Preconditions.assert_class('url', url, String)
      @authorization = HttpClient::Preconditions.assert_class_or_nil('authorization', opts.delete(:authorization), HttpClient::Authorization)
      HttpClient::Preconditions.assert_empty_opts(opts)
      HttpClient::Preconditions.check_state(url.match(/http.+/i), "URL[%s] must start with http" % url)
    end

    def request(path=nil)
      HttpClient::Preconditions.assert_class_or_nil('path', path, String)
      request = HttpClient::Request.new(URI.parse(@url + path.to_s))

      if @authorization
        request.with_auth(@authorization)
      else
        request
      end
    end
""")

    sb.append(service.resources.map { resource =>
      val modelPlural = resource.model.plural
      val className = Text.underscoreToInitCap(modelPlural)

      s"    def ${modelPlural}\n" +
      s"      @${modelPlural} ||= ${moduleName}::Clients::${className}.new(self)\n" +
      "    end"
    }.mkString("\n\n"))

    sb.append("  end")

    sb.mkString("\n")
  }

  def generateClientForResource(resource: Resource): String = {
    val className = Text.underscoreToInitCap(resource.model.plural)

    val sb = ListBuffer[String]()
    sb.append(s"    class ${className}")
    sb.append("")
    sb.append("      def initialize(client)")
    sb.append(s"        @client = HttpClient::Preconditions.assert_class('client', client, ${moduleName}::Client)")
    sb.append("      end")

    resource.operations.foreach { op =>
      val pathParams = op.parameters.filter { p => p.location == ParameterLocation.Path }
      val queryParams = op.parameters.filter { p => p.location == ParameterLocation.Query }
      val formParams = op.parameters.filter { p => p.location == ParameterLocation.Form }

      val rubyPath = op.path.split("/").map { name =>
        if (name.startsWith(":")) {
          "#{" + name.slice(1, name.length) + "}"
        } else {
          name
        }
      }.mkString("/")

      val methodName = Text.camelCaseToUnderscore(GeneratorUtil.urlToMethodName(resource.path, op.method, op.path)).toLowerCase

      val paramStrings = ListBuffer[String]()
      pathParams.map(_.name).foreach { n => paramStrings.append(n) }

      if (Util.isJsonDocumentMethod(op.method)) {
        op.body match {
          case None => paramStrings.append("hash")
          case Some(model: Model) => paramStrings.append(model.name)
        }
      }

      if (!queryParams.isEmpty) {
        paramStrings.append("incoming={}")
      }

      sb.append("")
      op.description.map { desc =>
        sb.append(GeneratorUtil.formatComment(desc, 6))
      }
      sb.append(s"      def ${methodName}(" + paramStrings.mkString(", ") + ")")

      pathParams.foreach { param =>

        val klass = param.paramtype match {
          case t: PrimitiveParameterType => rubyClass(t.datatype)
          case m: ModelParameterType => Text.underscoreToInitCap(m.model.name)
        }

        sb.append(s"        HttpClient::Preconditions.assert_class('${param.name}', ${param.name}, ${klass})")
      }

      if (!queryParams.isEmpty) {
        val paramBuilder = ListBuffer[String]()

        queryParams.foreach { param =>
          paramBuilder.append(s":${param.name} => ${parseArgument(param)}")
        }

        sb.append("        opts = HttpClient::Helper.symbolize_keys(incoming)")
        sb.append("        query = {")
        sb.append("          " + paramBuilder.mkString(",\n          "))
        sb.append("        }.delete_if { |k, v| v.nil? }")
      }

      val requestBuilder = new StringBuilder()
      requestBuilder.append("@client.request(\"" + rubyPath + "\")")

      if (!queryParams.isEmpty) {
        requestBuilder.append(".with_query(query)")
      }

      if (Util.isJsonDocumentMethod(op.method)) {
        op.body match {
          case None => {
            sb.append("        HttpClient::Preconditions.assert_class('hash', hash, Hash)")
            requestBuilder.append(".with_json(hash.to_json)")
          }
          case Some(model: Model) => {
            val klass = s"$moduleName::Models::${Text.underscoreToInitCap(model.name)}"
            sb.append(s"        HttpClient::Preconditions.assert_class('${model.name}', ${model.name}, $klass)")
            requestBuilder.append(s".with_json(${model.name}.to_hash.to_json)")
          }
        }
      }
      requestBuilder.append(s".${op.method.toLowerCase}")

      val responseBuilder = new StringBuilder()

      // TODO: match on all response codes
      op.responses.headOption.map { response =>
        response.datatype match {
          case Datatype.UnitType.name => {
            responseBuilder.append("\n        nil")
          }

          case resourceName: String => {
            if (op.responses.head.multiple) {
              responseBuilder.append(".map")
            }
            responseBuilder.append(s" { |hash| ${moduleName}::Models::${Text.underscoreToInitCap(resourceName)}.new(hash) }")
          }
        }
      }

      sb.append(s"        ${requestBuilder.toString}${responseBuilder.toString}")
      sb.append("      end")
    }

    sb.append("")
    sb.append("    end")

    sb.mkString("\n")
  }

  def generateModel(model: core.Model): String = {
    val className = Text.underscoreToInitCap(model.name)

    val sb = ListBuffer[String]()

    model.description.map { desc => sb.append(GeneratorUtil.formatComment(desc, 4)) }
    sb.append(s"    class $className\n")

    val enums = model.fields.filter { _.fieldtype.isInstanceOf[EnumerationFieldType] }.map { field =>
      RubyGemGenerator.generateEnumClass(model.name, field.name, field.fieldtype.asInstanceOf[EnumerationFieldType])
    }
    if (!enums.isEmpty) {
      sb.append(enums.mkString("\n\n").indent(6) + "\n")
    }

    sb.append("      attr_reader " + model.fields.map( f => s":${f.name}" ).mkString(", "))

    sb.append("")
    sb.append("      def initialize(incoming={})")
    sb.append("        opts = HttpClient::Helper.symbolize_keys(incoming)")

    model.fields.map { field =>
      sb.append(s"        @${field.name} = ${parseArgument(field)}")
    }

    sb.append("      end\n")

    sb.append("      def to_hash")
    sb.append("        {")
    sb.append(
      model.fields.map { field =>
        field.fieldtype match {
          case PrimitiveFieldType(datatype) => s":${field.name} => ${field.name}"
          case ModelFieldType(model) => s":${field.name} => ${field.name}.to_hash"
          case EnumerationFieldType(datatype, values) => s":${field.name} => ${field.name}.value"
        }
      }.mkString("            ", ",\n            ", "")
    )
    sb.append("        }")
    sb.append("      end\n")

    sb.append("    end")

    sb.mkString("\n")
  }

  private def parseArgument(field: Field): String = {
    field.fieldtype match {
      case PrimitiveFieldType(datatype: Datatype) => {
        parsePrimitiveArgument(field.name, datatype, field.required, field.default, field.multiple)
      }
      case ModelFieldType(model: Model) => {
        parseModelArgument(field.name, model, field.required, field.multiple)
      }
      case EnumerationFieldType(datatype: Datatype, values: Seq[String]) => {
        parseEnumArgument(field.name, field.required, field.multiple)
      }
    }

  }

  private def parseArgument(param: Parameter): String = {
    param.paramtype match {
      case dt: PrimitiveParameterType => {
        parsePrimitiveArgument(param.name, dt.datatype, param.required, param.default, param.multiple)
      }
      case mt: ModelParameterType => {
        parseModelArgument(param.name, mt.model, param.required, param.multiple)
      }
    }
  }

  private def parseModelArgument(name: String, model: Model, required: Boolean, multiple: Boolean): String = {
    val value = s"opts.delete(:${name})"
    val klass = Text.underscoreToInitCap(model.name)
    s"HttpClient::Helper.to_model_instance('${name}', ${klass}, ${value}, :required => $required, :multiple => $multiple)"
  }

  private def parseEnumArgument(name: String, required: Boolean, multiple: Boolean): String = {
    val value = s"opts.delete(:${name})"
    val klass = Text.underscoreToInitCap(name)
    s"HttpClient::Helper.to_klass('$name', $klass.apply($value), $klass, :required => $required, :multiple => $multiple)"
  }

  private def parsePrimitiveArgument(name: String, datatype: Datatype, required: Boolean, default: Option[String], multiple: Boolean): String = {
    val value = if (default.isEmpty) {
      s"opts.delete(:${name})"
    } else if (datatype == Datatype.StringType) {
      s"opts.delete(:${name}) || \'${default.get}\'"
    } else if (datatype == Datatype.BooleanType) {
      s"opts.has_key?(:${name}) ? (opts.delete(:${name}) ? true : false) : ${default.get}"
    } else {
      s"opts.delete(:${name}) || ${default.get}"
    }

    val hasValue = (required || !default.isEmpty)
    val assertMethod = if (hasValue) { "assert_class" } else { "assert_class_or_nil" }
    val klass = rubyClass(datatype)

    if (datatype == Datatype.DecimalType) {
      s"HttpClient::Helper.to_big_decimal('$name', $value, :required => ${required}, :multiple => ${multiple})"

    } else if (datatype == Datatype.UuidType) {
      s"HttpClient::Helper.to_uuid('$name', $value, :required => ${required}, :multiple => ${multiple})"

    } else if (datatype == Datatype.DateTimeIso8601Type) {
      s"HttpClient::Helper.to_date_time_iso8601('$name', $value, :required => ${required}, :multiple => ${multiple})"

    } else if (datatype == Datatype.BooleanType) {
      s"HttpClient::Helper.to_boolean('$name', $value, :required => ${required}, :multiple => ${multiple})"

    } else {
      s"HttpClient::Helper.to_klass('$name', $value, ${klass}, :required => ${required}, :multiple => ${multiple})"
    }

  }

  private def rubyClass(datatype: Datatype): String = {
    datatype match {
      case Datatype.StringType => "String"
      case Datatype.LongType => "Integer"
      case Datatype.DoubleType => "Float"
      case Datatype.IntegerType => "Integer"
      case Datatype.BooleanType => "String"
      case Datatype.DecimalType => "BigDecimal"
      case Datatype.UuidType => "String"
      case Datatype.DateTimeIso8601Type => "DateTime"
      case Datatype.MapType => "Hash"
      case Datatype.UnitType => "nil"
      case _ => {
        sys.error(s"Cannot map data type[${datatype}] to ruby class")
      }
    }
  }


}
