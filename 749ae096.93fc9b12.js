(window.webpackJsonp=window.webpackJsonp||[]).push([[7],{73:function(e,t,n){"use strict";n.r(t),n.d(t,"frontMatter",(function(){return c})),n.d(t,"metadata",(function(){return i})),n.d(t,"toc",(function(){return p})),n.d(t,"default",(function(){return u}));var r=n(3),a=n(7),o=(n(0),n(82)),c={title:"Structural Schema"},i={unversionedId:"structural-schema",id:"structural-schema",isDocsHomePage:!1,title:"Structural Schema",description:"In order to deploy Structural Schema, aka. JSON Schema, put JSON file in CLASSPATH at schema/.{json|js} path.",source:"@site/../docs/target/mdoc/structural-schema.md",slug:"/structural-schema",permalink:"/structural-schema",editUrl:"https://github.com/facebook/docusaurus/edit/master/website/../docs/target/mdoc/structural-schema.md",version:"current",sidebar:"docs",previous:{title:"Reconcile events",permalink:"/reconcile-events"},next:{title:"Dependencies",permalink:"/dependencies"}},p=[],s={toc:p};function u(e){var t=e.components,n=Object(a.a)(e,["components"]);return Object(o.b)("wrapper",Object(r.a)({},s,n,{components:t,mdxType:"MDXLayout"}),Object(o.b)("p",null,"In order to deploy Structural Schema, aka. JSON Schema, put JSON file in CLASSPATH at ",Object(o.b)("inlineCode",{parentName:"p"},"schema/<kind>.{json|js}")," path.\nFreya deploys JSON schema together with CR definition automatically during the Operator startup,\n",Object(o.b)("strong",{parentName:"p"},"only if CRD is not found"),"."),Object(o.b)("p",null,"For Kerberos Operator example, a JSON Schema is the following."),Object(o.b)("p",null,"At resources/schema/kerb.json:"),Object(o.b)("pre",null,Object(o.b)("code",{parentName:"pre",className:"language-json"},'{\n  "type": "object",\n  "properties": {\n    "spec": {\n      "type": "object",\n      "properties": {\n        "realm": {\n          "type": "string"\n        },\n        "principals": {\n          "type": "array",\n          "items": {\n            "type": "object",\n            "properties": {\n              "name": {\n                "type": "string"\n              },\n              "password": {\n                "type": "string"\n              },\n              "value": {\n                "type": "string"\n              }\n            },\n            "required": [\n              "name",\n              "password"\n            ]\n          }\n        }\n      },\n      "required": [\n        "realm",\n        "principals"        \n      ]\n    },\n    "status": {\n      "type": "object",\n      "properties": {\n        "ready": {\n          "type": "boolean"\n        }\n      }\n    }\n  }\n}\n')))}u.isMDXComponent=!0},82:function(e,t,n){"use strict";n.d(t,"a",(function(){return l})),n.d(t,"b",(function(){return f}));var r=n(0),a=n.n(r);function o(e,t,n){return t in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}function c(e,t){var n=Object.keys(e);if(Object.getOwnPropertySymbols){var r=Object.getOwnPropertySymbols(e);t&&(r=r.filter((function(t){return Object.getOwnPropertyDescriptor(e,t).enumerable}))),n.push.apply(n,r)}return n}function i(e){for(var t=1;t<arguments.length;t++){var n=null!=arguments[t]?arguments[t]:{};t%2?c(Object(n),!0).forEach((function(t){o(e,t,n[t])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(n)):c(Object(n)).forEach((function(t){Object.defineProperty(e,t,Object.getOwnPropertyDescriptor(n,t))}))}return e}function p(e,t){if(null==e)return{};var n,r,a=function(e,t){if(null==e)return{};var n,r,a={},o=Object.keys(e);for(r=0;r<o.length;r++)n=o[r],t.indexOf(n)>=0||(a[n]=e[n]);return a}(e,t);if(Object.getOwnPropertySymbols){var o=Object.getOwnPropertySymbols(e);for(r=0;r<o.length;r++)n=o[r],t.indexOf(n)>=0||Object.prototype.propertyIsEnumerable.call(e,n)&&(a[n]=e[n])}return a}var s=a.a.createContext({}),u=function(e){var t=a.a.useContext(s),n=t;return e&&(n="function"==typeof e?e(t):i(i({},t),e)),n},l=function(e){var t=u(e.components);return a.a.createElement(s.Provider,{value:t},e.children)},m={inlineCode:"code",wrapper:function(e){var t=e.children;return a.a.createElement(a.a.Fragment,{},t)}},d=a.a.forwardRef((function(e,t){var n=e.components,r=e.mdxType,o=e.originalType,c=e.parentName,s=p(e,["components","mdxType","originalType","parentName"]),l=u(n),d=r,f=l["".concat(c,".").concat(d)]||l[d]||m[d]||o;return n?a.a.createElement(f,i(i({ref:t},s),{},{components:n})):a.a.createElement(f,i({ref:t},s))}));function f(e,t){var n=arguments,r=t&&t.mdxType;if("string"==typeof e||r){var o=n.length,c=new Array(o);c[0]=d;var i={};for(var p in t)hasOwnProperty.call(t,p)&&(i[p]=t[p]);i.originalType=e,i.mdxType="string"==typeof e?e:r,c[1]=i;for(var s=2;s<o;s++)c[s]=n[s];return a.a.createElement.apply(null,c)}return a.a.createElement.apply(null,n)}d.displayName="MDXCreateElement"}}]);