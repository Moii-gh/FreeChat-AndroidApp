import "./styles.css";
import {
  defaultLanguage,
  languageOptions,
  translations,
  type LanguageCode,
  type LanguageOption,
  type PolicySection,
  type Translation
} from "./translations";

const logoUrl = new URL("./assets/logo.png", import.meta.url).href;
const languageStorageKey = "freechat.website.language";
const closeAnimationMs = 240;

type PolicyNavigationItem = {
  id: string;
  title: string;
  number: number;
};

const getAppRoot = () => {
  const root = document.querySelector<HTMLDivElement>("#app");

  if (!root) {
    throw new Error("Root element #app was not found.");
  }

  return root;
};

const appRoot = getAppRoot();

const supportedLanguageCodes = new Set<LanguageCode>(
  languageOptions.map((language) => language.code)
);

let currentLanguage = getInitialLanguage();
let isLanguageSheetOpen = false;
let removeKeyboardListener: (() => void) | undefined;

function isLanguageCode(value: string | null | undefined): value is LanguageCode {
  return Boolean(value && supportedLanguageCodes.has(value as LanguageCode));
}

function resolveLanguageCode(value: string | null | undefined): LanguageCode | undefined {
  if (!value) return undefined;

  const normalized = value.toLowerCase();
  const exactMatch = languageOptions.find(
    (language) =>
      language.code === normalized || language.htmlLang.toLowerCase() === normalized
  );

  if (exactMatch) return exactMatch.code;

  const prefix = normalized.split("-")[0];
  return isLanguageCode(prefix) ? prefix : undefined;
}

function getStoredLanguage(): LanguageCode | undefined {
  try {
    return resolveLanguageCode(window.localStorage.getItem(languageStorageKey));
  } catch {
    return undefined;
  }
}

function getInitialLanguage(): LanguageCode {
  return (
    getStoredLanguage() ??
    resolveLanguageCode(window.navigator.language) ??
    defaultLanguage
  );
}

function persistLanguage(language: LanguageCode) {
  try {
    window.localStorage.setItem(languageStorageKey, language);
  } catch {
    // localStorage can be unavailable in private or restricted contexts.
  }
}

function getLanguageOption(language: LanguageCode): LanguageOption {
  return (
    languageOptions.find((option) => option.code === language) ??
    languageOptions[0]
  );
}

function formatSectionTitle(section: PolicyNavigationItem) {
  return `${section.number}. ${section.title}`;
}

function createSectionNavigation(sections: PolicySection[]): PolicyNavigationItem[] {
  return sections.map((section, index) => ({
    id: section.id,
    title: section.title,
    number: index + 1
  }));
}

function renderPoints(points?: string[]) {
  if (!points?.length) return "";

  return `
    <ul>
      ${points.map((point) => `<li>${point}</li>`).join("")}
    </ul>
  `;
}

function renderSection(
  section: PolicySection,
  index: number,
  navigation: PolicyNavigationItem[]
) {
  return `
    <section id="${section.id}" class="policy-section">
      <h2>${formatSectionTitle(navigation[index])}</h2>
      ${section.paragraphs.map((paragraph) => `<p>${paragraph}</p>`).join("")}
      ${renderPoints(section.points)}
      ${section.note ? `<div class="notice">${section.note}</div>` : ""}
    </section>
  `;
}

function renderLanguageTrigger(
  translation: Translation,
  selectedLanguage: LanguageOption,
  className = ""
) {
  return `
    <button
      class="language-trigger ${className}"
      type="button"
      data-language-trigger
      aria-haspopup="dialog"
      aria-expanded="${isLanguageSheetOpen}"
      aria-controls="language-sheet"
      aria-label="${translation.language.triggerAria}"
    >
      <span class="language-trigger__flag" aria-hidden="true">
        <span class="flag-icon flag-icon--${selectedLanguage.code}"></span>
      </span>
      <span class="language-trigger__copy">
        <span>${translation.language.label}</span>
        <strong>${selectedLanguage.language}</strong>
      </span>
      <span class="language-trigger__chevron" aria-hidden="true"></span>
    </button>
  `;
}

function renderLanguageOption(
  option: LanguageOption,
  selectedLanguage: LanguageCode,
  translation: Translation
) {
  const isSelected = option.code === selectedLanguage;

  return `
    <button
      class="language-option ${isSelected ? "is-selected" : ""}"
      type="button"
      data-language-code="${option.code}"
      aria-pressed="${isSelected}"
    >
      <span class="language-option__flag" aria-hidden="true">
        <span class="flag-icon flag-icon--${option.code}"></span>
      </span>
      <span class="language-option__content">
        <strong>${option.language}</strong>
        <span>${option.country}</span>
      </span>
      <span class="language-option__status">${isSelected ? translation.language.selected : ""}</span>
    </button>
  `;
}

function renderLanguageSheet(translation: Translation) {
  if (!isLanguageSheetOpen) return "";

  return `
    <div class="language-modal" data-language-modal>
      <button
        class="language-backdrop"
        type="button"
        data-language-close
        aria-label="${translation.language.close}"
      ></button>
      <section
        class="language-sheet"
        id="language-sheet"
        role="dialog"
        aria-modal="true"
        aria-labelledby="language-sheet-title"
        aria-describedby="language-sheet-description"
        tabindex="-1"
      >
        <div class="language-sheet__header">
          <div>
            <p class="language-sheet__eyebrow">${translation.language.label}</p>
            <h2 id="language-sheet-title">${translation.language.sheetTitle}</h2>
            <p id="language-sheet-description">${translation.language.sheetDescription}</p>
          </div>
          <button class="language-close" type="button" data-language-close aria-label="${translation.language.close}">
            <span aria-hidden="true"></span>
          </button>
        </div>
        <div class="language-list">
          ${languageOptions
            .map((option) => renderLanguageOption(option, currentLanguage, translation))
            .join("")}
        </div>
      </section>
    </div>
  `;
}

function applyDocumentLanguage(translation: Translation, selectedLanguage: LanguageOption) {
  document.documentElement.lang = selectedLanguage.htmlLang;
  document.title = translation.meta.title;

  document
    .querySelector<HTMLMetaElement>('meta[name="description"]')
    ?.setAttribute("content", translation.meta.description);
}

function renderApp() {
  const translation = translations[currentLanguage];
  const selectedLanguage = getLanguageOption(currentLanguage);
  const sectionNavigation = createSectionNavigation(translation.sections);

  applyDocumentLanguage(translation, selectedLanguage);
  document.body.classList.toggle("is-language-sheet-open", isLanguageSheetOpen);

  appRoot.innerHTML = `
    <div class="site-frame">
      <header class="site-header">
        <a class="brand-link" href="#" aria-label="FreeChat">
          <img class="brand-logo app-logo" src="${logoUrl}" alt="" />
          <span>FreeChat</span>
        </a>
        <nav class="top-nav" aria-label="${translation.nav.ariaLabel}">
          <a href="#general">${translation.nav.policy}</a>
          <a href="#rights">${translation.nav.rights}</a>
          <a href="#contacts">${translation.nav.contacts}</a>
        </nav>
        <div class="header-action">
          ${renderLanguageTrigger(translation, selectedLanguage)}
          <a class="return-link" href="#" id="return-to-app">${translation.actions.returnToApp}</a>
          <span class="return-hint" id="return-hint" aria-live="polite"></span>
        </div>
      </header>

      <main class="document-shell">
        <aside class="toc-panel" aria-label="${translation.toc.title}">
          <p class="toc-title">${translation.toc.title}</p>
          <nav>
            ${sectionNavigation
              .map((section) => `<a href="#${section.id}">${formatSectionTitle(section)}</a>`)
              .join("")}
          </nav>
          <div class="language-block">
            ${renderLanguageTrigger(translation, selectedLanguage, "language-trigger--wide")}
          </div>
        </aside>

        <article class="document-content" aria-labelledby="page-title">
          <p class="updated">${translation.meta.lastUpdated}</p>
          <h1 id="page-title">${translation.hero.title}</h1>
          ${translation.hero.lede.map((paragraph) => `<p class="lede">${paragraph}</p>`).join("")}
          ${translation.sections
            .map((section, index) => renderSection(section, index, sectionNavigation))
            .join("")}
        </article>
      </main>
    </div>

    <footer class="site-footer">
      <span>FreeChat</span>
      <span>${translation.footer.privacy}</span>
    </footer>

    ${renderLanguageSheet(translation)}
  `;

  bindEvents();
}

function openLanguageSheet() {
  isLanguageSheetOpen = true;
  renderApp();
}

function closeLanguageSheet() {
  const modal = document.querySelector<HTMLElement>("[data-language-modal]");

  if (!modal) {
    isLanguageSheetOpen = false;
    renderApp();
    return;
  }

  modal.classList.add("is-closing");
  document.body.classList.remove("is-language-sheet-open");

  window.setTimeout(() => {
    isLanguageSheetOpen = false;
    renderApp();
  }, closeAnimationMs);
}

function selectLanguage(language: LanguageCode) {
  if (language !== currentLanguage) {
    currentLanguage = language;
    persistLanguage(language);
    renderApp();
  }

  closeLanguageSheet();
}

function bindEvents() {
  removeKeyboardListener?.();
  removeKeyboardListener = undefined;

  document.querySelectorAll<HTMLButtonElement>("[data-language-trigger]").forEach((button) => {
    button.addEventListener("click", openLanguageSheet);
  });

  document.querySelectorAll<HTMLButtonElement>("[data-language-close]").forEach((button) => {
    button.addEventListener("click", closeLanguageSheet);
  });

  document.querySelectorAll<HTMLButtonElement>("[data-language-code]").forEach((button) => {
    button.addEventListener("click", () => {
      const language = button.dataset.languageCode;
      if (isLanguageCode(language)) selectLanguage(language);
    });
  });

  const returnLink = document.querySelector<HTMLAnchorElement>("#return-to-app");
  const returnHint = document.querySelector<HTMLSpanElement>("#return-hint");
  const logoImage = document.querySelector<HTMLImageElement>(".app-logo");

  returnLink?.addEventListener("click", (event) => {
    event.preventDefault();
    if (!returnHint) return;

    returnHint.textContent = translations[currentLanguage].actions.returnHint;
    returnLink.classList.add("is-pulsed");
    window.setTimeout(() => returnLink.classList.remove("is-pulsed"), 720);
  });

  logoImage?.addEventListener("error", () => {
    logoImage.replaceWith(
      Object.assign(document.createElement("span"), {
        className: "brand-placeholder",
        textContent: "FC"
      })
    );
  });

  if (isLanguageSheetOpen) {
    const onKeydown = (event: KeyboardEvent) => {
      if (event.key === "Escape") closeLanguageSheet();
    };

    document.addEventListener("keydown", onKeydown);
    removeKeyboardListener = () => document.removeEventListener("keydown", onKeydown);

    window.requestAnimationFrame(() => {
      document
        .querySelector<HTMLButtonElement>(".language-option.is-selected")
        ?.focus({ preventScroll: true });
    });
  }
}

renderApp();
