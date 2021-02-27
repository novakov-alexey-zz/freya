module.exports = {
  title: "Freya",
  tagline: "Kubernetes Operator library for Scala",
  url: "https://novakov-alexey.github.io/freya",
  baseUrl: "/freya/",
  onBrokenLinks: "log",
  onBrokenMarkdownLinks: "warn",
  favicon: "img/favicon.png",
  organizationName: "novakov-alexey",
  projectName: "freya", 
  themeConfig: {
    navbar: {
      title: "Freya",
      logo: {
        alt: "Freya Logo",
        src: "img/navbar_brand.svg",
      },
      items: [
        {
          href: "https://github.com/novakov-alexey/freya",
          label: "GitHub",
          position: "right",
        },
      ],
    },
    footer: {
      style: "dark",
      links: [],
      copyright: `Copyright © ${new Date().getFullYear()} Freya. Built with Docusaurus.`,
    },
    prism: {
      additionalLanguages: ["scala"],
    },
  },
  presets: [
    [
      "@docusaurus/preset-classic",
      {
        docs: {
          path: "../docs/target/mdoc",
          sidebarPath: require.resolve("./sidebars.js"),          
          editUrl: "https://github.com/novakov-alexey/freya/edit/master/website/",
          routeBasePath: "/"
        },
        theme: {
          customCss: require.resolve("./src/css/custom.css"),
        },
      },
    ],
  ],
};
