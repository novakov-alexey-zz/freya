module.exports = {
  title: "Freya",
  tagline: "Kubernetes Operator library for Scala",
  url: "https://novakov-alexey.github.io/freya",
  baseUrl: "/freya/",
  onBrokenLinks: "log",
  onBrokenMarkdownLinks: "warn",
  favicon: "img/favicon.png",
  organizationName: "novakov-alexey", // Usually your GitHub org/user name.
  projectName: "freya", // Usually your repo name.
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
      style: 'dark',
      links: [
        // {
        //   title: 'Docs',
        //   items: [
        //     {
        //       label: 'Style Guide',
        //       to: 'docs/',
        //     },
        //     {
        //       label: 'Second Doc',
        //       to: 'docs/doc2/',
        //     },
        //   ],
        // },
        // {
        //   title: 'Community',
        //   items: [
        //     {
        //       label: 'Stack Overflow',
        //       href: 'https://stackoverflow.com/questions/tagged/docusaurus',
        //     },
        //     {
        //       label: 'Discord',
        //       href: 'https://discordapp.com/invite/docusaurus',
        //     },
        //     {
        //       label: 'Twitter',
        //       href: 'https://twitter.com/docusaurus',
        //     },
        //   ],
        // },
        // {
        //   title: 'More',
        //   items: [
        //     // {
        //     //   label: 'Blog',
        //     //   to: 'blog',
        //     // },
        //     {
        //       label: 'GitHub',
        //       href: 'https://github.com/facebook/docusaurus',
        //     },
        //   ],
        // },
      ],
      copyright: `Copyright © ${new Date().getFullYear()} Freya. Built with Docusaurus.`,
    },
    prism: {
      additionalLanguages: ['scala'],
    },
  },
  presets: [
    [
      '@docusaurus/preset-classic',
      {
        docs: {
          path: "../docs/target/mdoc",
          sidebarPath: require.resolve("./sidebars.js"),
          // Please change this to your repo.
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
