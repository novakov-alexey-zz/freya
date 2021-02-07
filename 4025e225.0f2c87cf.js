(window.webpackJsonp=window.webpackJsonp||[]).push([[5],{71:function(e,t,n){"use strict";n.r(t),n.d(t,"frontMatter",(function(){return i})),n.d(t,"metadata",(function(){return c})),n.d(t,"toc",(function(){return l})),n.d(t,"default",(function(){return u}));var r=n(3),o=n(7),a=(n(0),n(82)),i={title:"Reconcile events"},c={unversionedId:"reconcile-events",id:"reconcile-events",isDocsHomePage:!1,title:"Reconcile events",description:"Freya can start your operator with parallel reconciler thread, which is puling current",source:"@site/../docs/target/mdoc/reconcile-events.md",slug:"/reconcile-events",permalink:"/reconcile-events",editUrl:"https://github.com/facebook/docusaurus/edit/master/website/../docs/target/mdoc/reconcile-events.md",version:"current",sidebar:"docs",previous:{title:"Configuration",permalink:"/configuration"},next:{title:"Structural Schema",permalink:"/structural-schema"}},l=[],s={toc:l};function u(e){var t=e.components,n=Object(o.a)(e,["components"]);return Object(a.b)("wrapper",Object(r.a)({},s,n,{components:t,mdxType:"MDXLayout"}),Object(a.b)("p",null,"Freya can start your operator with parallel reconciler thread, which is puling current\nresources (CRs or ConfigMaps) at specified time interval. This feature allows to pro-actively check\nexisting resources and make sure that desired configuration is reflected in terms of Kubernetes objects.\nIt is also useful, when your controller failed to handle real-time event. It can process such event later,\nonce reconcile process is getting desired resources and pushes them to controller, so that controller can process those\nevents second or n-th time. Reconciler always returns all resources regardless they were already handled\nby your operator or not. Thus, it is important that your operators works in ",Object(a.b)("inlineCode",{parentName:"p"},"idempotent")," manner. "),Object(a.b)("p",null,"Using ",Object(a.b)("inlineCode",{parentName:"p"},"Kerb")," example:"),Object(a.b)("pre",null,Object(a.b)("code",{parentName:"pre",className:"language-scala"},'final case class Principal(name: String, password: String, value: String = "")\nfinal case class Kerb(realm: String, principals: List[Principal])\n')),Object(a.b)("pre",null,Object(a.b)("code",{parentName:"pre",className:"language-scala"},'import freya.Configuration.CrdConfig\nimport freya.K8sNamespace.Namespace\nimport freya.models._\nimport freya.json.jackson._\nimport freya.{Controller, Operator}\nimport cats.syntax.functor._\nimport cats.effect._\nimport scala.concurrent.ExecutionContext\nimport scala.concurrent.duration._\nimport com.typesafe.scalalogging.LazyLogging\nimport io.fabric8.kubernetes.client.{KubernetesClient, DefaultKubernetesClient}  \nimport scala.annotation.unused\n\nval cfg = CrdConfig(Namespace("test"), prefix = "io.myorg.kerboperator")\nval client = IO(new DefaultKubernetesClient)\n\n// p.s. use IOApp as in previous examples instead of below timer and cs values\nimplicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)  \nimplicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)\n\n// override reconcile method\n\nclass KerbController[F[_]](@unused client: KubernetesClient)(\n  implicit F: ConcurrentEffect[F]\n) \n  extends Controller[F, Kerb, Unit] with LazyLogging {\n\n  override def reconcile(krb: CustomResource[Kerb, Unit]): F[NoStatus] =\n    F.delay(logger.info(s"Kerb to reconcile: ${krb.spec}, ${krb.metadata}")).void \n}\n\nOperator\n  .ofCrd[IO, Kerb](cfg, client, (c: KubernetesClient) => new KerbController[IO](c))\n  .withReconciler(1.minute)\n  .withRestart()\n')),Object(a.b)("p",null,"Above configuration will call controller's ",Object(a.b)("inlineCode",{parentName:"p"},"reconcile")," method every minute, since operator start, in case at least\none CR/ConfigMap resource is found."))}u.isMDXComponent=!0},82:function(e,t,n){"use strict";n.d(t,"a",(function(){return p})),n.d(t,"b",(function(){return d}));var r=n(0),o=n.n(r);function a(e,t,n){return t in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}function i(e,t){var n=Object.keys(e);if(Object.getOwnPropertySymbols){var r=Object.getOwnPropertySymbols(e);t&&(r=r.filter((function(t){return Object.getOwnPropertyDescriptor(e,t).enumerable}))),n.push.apply(n,r)}return n}function c(e){for(var t=1;t<arguments.length;t++){var n=null!=arguments[t]?arguments[t]:{};t%2?i(Object(n),!0).forEach((function(t){a(e,t,n[t])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(n)):i(Object(n)).forEach((function(t){Object.defineProperty(e,t,Object.getOwnPropertyDescriptor(n,t))}))}return e}function l(e,t){if(null==e)return{};var n,r,o=function(e,t){if(null==e)return{};var n,r,o={},a=Object.keys(e);for(r=0;r<a.length;r++)n=a[r],t.indexOf(n)>=0||(o[n]=e[n]);return o}(e,t);if(Object.getOwnPropertySymbols){var a=Object.getOwnPropertySymbols(e);for(r=0;r<a.length;r++)n=a[r],t.indexOf(n)>=0||Object.prototype.propertyIsEnumerable.call(e,n)&&(o[n]=e[n])}return o}var s=o.a.createContext({}),u=function(e){var t=o.a.useContext(s),n=t;return e&&(n="function"==typeof e?e(t):c(c({},t),e)),n},p=function(e){var t=u(e.components);return o.a.createElement(s.Provider,{value:t},e.children)},m={inlineCode:"code",wrapper:function(e){var t=e.children;return o.a.createElement(o.a.Fragment,{},t)}},f=o.a.forwardRef((function(e,t){var n=e.components,r=e.mdxType,a=e.originalType,i=e.parentName,s=l(e,["components","mdxType","originalType","parentName"]),p=u(n),f=r,d=p["".concat(i,".").concat(f)]||p[f]||m[f]||a;return n?o.a.createElement(d,c(c({ref:t},s),{},{components:n})):o.a.createElement(d,c({ref:t},s))}));function d(e,t){var n=arguments,r=t&&t.mdxType;if("string"==typeof e||r){var a=n.length,i=new Array(a);i[0]=f;var c={};for(var l in t)hasOwnProperty.call(t,l)&&(c[l]=t[l]);c.originalType=e,c.mdxType="string"==typeof e?e:r,i[1]=c;for(var s=2;s<a;s++)i[s]=n[s];return o.a.createElement.apply(null,i)}return o.a.createElement.apply(null,n)}f.displayName="MDXCreateElement"}}]);