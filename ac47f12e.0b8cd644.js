(window.webpackJsonp=window.webpackJsonp||[]).push([[10],{76:function(e,n,a){"use strict";a.r(n),a.d(n,"frontMatter",(function(){return s})),a.d(n,"metadata",(function(){return o})),a.d(n,"toc",(function(){return i})),a.d(n,"default",(function(){return p}));var r=a(3),t=a(7),c=(a(0),a(82)),s={title:"Resource parsing"},o={unversionedId:"resource-parsing",id:"resource-parsing",isDocsHomePage:!1,title:"Resource parsing",description:"Custom resources or ConfigMaps are parsed into a user defined case class(es). Besides parsing, Freya converts",source:"@site/../docs/target/mdoc/resource-parsing.md",slug:"/resource-parsing",permalink:"/freya/resource-parsing",editUrl:"https://github.com/novakov-alexey/freya/edit/master/website/../docs/target/mdoc/resource-parsing.md",version:"current",sidebar:"docs",previous:{title:"ConfigMap Operator",permalink:"/freya/configmap-operator"},next:{title:"Configuration",permalink:"/freya/configuration"}},i=[{value:"Usage",id:"usage",children:[]},{value:"Simple example",id:"simple-example",children:[{value:"Jackson",id:"jackson",children:[]},{value:"Circe",id:"circe",children:[]}]},{value:"Advanced example",id:"advanced-example",children:[{value:"Jackson",id:"jackson-1",children:[]},{value:"Circe",id:"circe-1",children:[]}]}],l={toc:i};function p(e){var n=e.components,a=Object(t.a)(e,["components"]);return Object(c.b)("wrapper",Object(r.a)({},l,a,{components:n,mdxType:"MDXLayout"}),Object(c.b)("p",null,"Custom resources or ConfigMaps are parsed into a user defined case class(es). Besides parsing, Freya converts\na user defined case class for status into a JSON string."),Object(c.b)("p",null,"Supported libraries to parse JSON/YAML:"),Object(c.b)("p",null,"Circe:"),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},'"io.github.novakov-alexey" %% "freya-circe" % "0.2.12" \n')),Object(c.b)("p",null,"Jackson:"),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},'"io.github.novakov-alexey" %% "freya-jackson" % "0.2.12"\n')),Object(c.b)("h2",{id:"usage"},"Usage"),Object(c.b)("p",null,"Add import statement at place of constructing your operator:"),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},"import freya.json.circe._\n")),Object(c.b)("p",null,"or"),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},"import freya.json.jackson._\n")),Object(c.b)("h2",{id:"simple-example"},"Simple example"),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},'final case class Principal(name: String, password: String, value: String = "")\nfinal case class Kerb(realm: String, principals: List[Principal])\nfinal case class Status(ready: Boolean = false)\n')),Object(c.b)("h3",{id:"jackson"},"Jackson"),Object(c.b)("p",null,"Jackson Scala module allows to parse simple case classes (no ADT, recursion, etc.) automatically, i.e. no extra code\nneeds to be written. Thus, only import of the Freya Jackson module is needed."),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},"import freya.json.jackson._\n")),Object(c.b)("h3",{id:"circe"},"Circe"),Object(c.b)("p",null,"Circe can derive its decoder/encoders automatically, when using its ",Object(c.b)("inlineCode",{parentName:"p"},"generic")," module with special import. "),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},"import freya.json.circe._\n// this import will derive required decoders/encoders \n// for your spec and status case classes\nimport io.circe.generic.auto._\n")),Object(c.b)("p",null,"Circe auto codecs derivation requires below module in your dependency settings:"),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},'"io.circe" %% "circe-generic" % circeVersion\n')),Object(c.b)("h2",{id:"advanced-example"},"Advanced example"),Object(c.b)("p",null,"Let's use ADT (algebraic data types) to design custom resource case class for ",Object(c.b)("inlineCode",{parentName:"p"},"Password")," and\n",Object(c.b)("inlineCode",{parentName:"p"},"Secret")," properties. Also, some classes will have default values."),Object(c.b)("h3",{id:"jackson-1"},"Jackson"),Object(c.b)("p",null,"Jackson provides several annotations to configure deserialisation for enum-like classes:"),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},'import com.fasterxml.jackson.annotation.JsonSubTypes.Type\nimport com.fasterxml.jackson.annotation.JsonTypeInfo.Id\nimport com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}\nimport Secret.{Keytab, KeytabAndPassword}\nimport Password.{Static, Random}\n\n@JsonTypeInfo(use = Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")\n@JsonSubTypes(\n  Array(\n    new Type(value = classOf[Static], name = "static"), \n    new Type(value = classOf[Random], name = "random")\n  )\n)\nsealed trait Password\nobject Password {\n  final case class Static(value: String) extends Password\n  final case class Random() extends Password\n}\n\n@JsonTypeInfo(use = Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")\n@JsonSubTypes(\n  Array(\n    new Type(value = classOf[Keytab], name = "Keytab"), \n    new Type(value = classOf[KeytabAndPassword], name = "KeytabAndPassword")\n  )\n)\nsealed trait Secret {\n  val name: String\n}\nobject Secret {\n  final case class Keytab(name: String) extends Secret\n  final case class KeytabAndPassword(name: String) extends Secret\n}\n\nfinal case class Principal(\n  name: String, password: Password, \n  keytab: String, secret: Secret\n)\nfinal case class Krb(realm: String, principals: List[Principal])\nfinal case class Status(\n  processed: Boolean, lastPrincipalCount: Int, \n  totalPrincipalCount: Int, error: String = ""\n)\n')),Object(c.b)("h3",{id:"circe-1"},"Circe"),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},'sealed trait Password\nfinal case class Static(value: String) extends Password\nfinal case class Random() extends Password\n\nsealed trait Secret {\n  val name: String\n}\n\nfinal case class Keytab(name: String) extends Secret\nfinal case class KeytabAndPassword(name: String) extends Secret\n\nfinal case class Principal(\n  name: String, password: Password = Random(), \n  keytab: String, secret: Secret\n)\nfinal case class Krb(realm: String, principals: List[Principal])\nfinal case class Status(\n  processed: Boolean, lastPrincipalCount: Int, \n  totalPrincipalCount: Int, error: String = ""\n)\n')),Object(c.b)("p",null,"Circe provides generic-extras module to cope with above hierarchy of case classes:"),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},'// note: it is separate Circe module called generic-extras, \n// which has its own version.\n"io.circe" %% "circe-generic-extras" % circeExtrasVersion\n')),Object(c.b)("p",null,"Define encoders and decoders. Let's wrap decoders into a Scala trait for later convenient injection into the operator\nconstruction site. However, usage of a trait is not necessarily:"),Object(c.b)("pre",null,Object(c.b)("code",{parentName:"pre",className:"language-scala"},'import cats.syntax.functor._\nimport io.circe.Decoder\nimport io.circe.generic.extras.Configuration\nimport io.circe.generic.extras.auto._\n\ntrait Codecs {\n  implicit val genConfig: Configuration =\n    Configuration.default.withDiscriminator("type").withDefaults\n\n  implicit val decodePassword: Decoder[Password] =\n    List[Decoder[Password]](Decoder[Static].widen, Decoder.const(Random()).widen)\n      .reduceLeft(_.or(_))\n\n  implicit val decodeSecret: Decoder[Secret] =\n    List[Decoder[Secret]](Decoder[Keytab].widen, Decoder[KeytabAndPassword].widen)\n      .reduceLeft(_.or(_))\n}\n')),Object(c.b)("p",null,"Then mixin or import all implicit values from the ",Object(c.b)("inlineCode",{parentName:"p"},"Codecs")," trait into the operator construction site. "))}p.isMDXComponent=!0},82:function(e,n,a){"use strict";a.d(n,"a",(function(){return d})),a.d(n,"b",(function(){return m}));var r=a(0),t=a.n(r);function c(e,n,a){return n in e?Object.defineProperty(e,n,{value:a,enumerable:!0,configurable:!0,writable:!0}):e[n]=a,e}function s(e,n){var a=Object.keys(e);if(Object.getOwnPropertySymbols){var r=Object.getOwnPropertySymbols(e);n&&(r=r.filter((function(n){return Object.getOwnPropertyDescriptor(e,n).enumerable}))),a.push.apply(a,r)}return a}function o(e){for(var n=1;n<arguments.length;n++){var a=null!=arguments[n]?arguments[n]:{};n%2?s(Object(a),!0).forEach((function(n){c(e,n,a[n])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(a)):s(Object(a)).forEach((function(n){Object.defineProperty(e,n,Object.getOwnPropertyDescriptor(a,n))}))}return e}function i(e,n){if(null==e)return{};var a,r,t=function(e,n){if(null==e)return{};var a,r,t={},c=Object.keys(e);for(r=0;r<c.length;r++)a=c[r],n.indexOf(a)>=0||(t[a]=e[a]);return t}(e,n);if(Object.getOwnPropertySymbols){var c=Object.getOwnPropertySymbols(e);for(r=0;r<c.length;r++)a=c[r],n.indexOf(a)>=0||Object.prototype.propertyIsEnumerable.call(e,a)&&(t[a]=e[a])}return t}var l=t.a.createContext({}),p=function(e){var n=t.a.useContext(l),a=n;return e&&(a="function"==typeof e?e(n):o(o({},n),e)),a},d=function(e){var n=p(e.components);return t.a.createElement(l.Provider,{value:n},e.children)},u={inlineCode:"code",wrapper:function(e){var n=e.children;return t.a.createElement(t.a.Fragment,{},n)}},b=t.a.forwardRef((function(e,n){var a=e.components,r=e.mdxType,c=e.originalType,s=e.parentName,l=i(e,["components","mdxType","originalType","parentName"]),d=p(a),b=r,m=d["".concat(s,".").concat(b)]||d[b]||u[b]||c;return a?t.a.createElement(m,o(o({ref:n},l),{},{components:a})):t.a.createElement(m,o({ref:n},l))}));function m(e,n){var a=arguments,r=n&&n.mdxType;if("string"==typeof e||r){var c=a.length,s=new Array(c);s[0]=b;var o={};for(var i in n)hasOwnProperty.call(n,i)&&(o[i]=n[i]);o.originalType=e,o.mdxType="string"==typeof e?e:r,s[1]=o;for(var l=2;l<c;l++)s[l]=a[l];return t.a.createElement.apply(null,s)}return t.a.createElement.apply(null,a)}b.displayName="MDXCreateElement"}}]);