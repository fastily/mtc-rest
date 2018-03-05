package mtc;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fastily.jwiki.core.MQuery;
import fastily.jwiki.core.NS;
import fastily.jwiki.core.WParser;
import fastily.jwiki.core.Wiki;
import fastily.jwiki.core.WParser.WTemplate;
import fastily.jwiki.core.WParser.WikiText;
import fastily.jwiki.dwrap.ImageInfo;
import fastily.jwiki.util.FL;
import fastily.jwiki.util.FSystem;

/**
 * Business Logic for MTC. Contains shared methods, constants, and Objects.
 * 
 * @author Fastily
 *
 */
public class MTC
{
	/**
	 * Cached results of whether a given template exists on Commons.
	 */
	protected HashMap<String, Boolean> ctpCache = new HashMap<>();

	/**
	 * The Wiki objects to use
	 */
	protected Wiki enwp = new Wiki("en.wikipedia.org"), com = new Wiki("commons.wikimedia.org");

	/**
	 * Regex matching Copy to Commons templates.
	 */
	protected String mtcRegex;

	/**
	 * Contains redirect data for license tags
	 */
	protected HashMap<String, String> tpMap = new HashMap<>();

	/**
	 * Files with these categories should not be transferred.
	 */
	protected HashSet<String> blacklist;

	/**
	 * Files must be members of at least one of the following categories to be eligible for transfer.
	 */
	protected HashSet<String> whitelist;

	/**
	 * Constructor, creates an MTC object.
	 */
	public MTC()
	{
		String fullname = "Wikipedia:MTC!", bln = fullname + "/Blacklist", wln = fullname + "/Whitelist";
		// Generate whitelist & blacklist
		HashMap<String, ArrayList<String>> l = MQuery.getLinksOnPage(enwp, FL.toSAL(bln, wln));
		blacklist = new HashSet<>(l.get(bln));
		whitelist = new HashSet<>(l.get(wln));

		// Process template redirect data
		for (String line : enwp.getPageText(fullname + "/Redirects").split("\n"))
			if (!line.startsWith("<") && !line.isEmpty())
			{
				String[] splits = line.split("\\|");
				for (String s : splits)
					tpMap.put(s, splits[0]);
			}

		// Setup mtcRegex
		ArrayList<String> rtl = enwp.nss(enwp.whatLinksHere("Template:Copy to Wikimedia Commons", true));
		rtl.add("Copy to Wikimedia Commons");
		mtcRegex = "(?si)\\{\\{(" + FL.pipeFence(rtl) + ").*?\\}\\}";
	}

	/**
	 * Creates TransferFile obejcts from a List of titles. Also filters (if enabled) and auto-resolves Commons filenames
	 * for transfer candidates.
	 * 
	 * @param titles The List of enwp files to transfer
	 * @return An ArrayList of TransferObject objects.
	 */
	/**
	 * Creates FileInfo objects from a list of titles. If a file is not eligible, a FileInfo object will not be returned
	 * for it.
	 * 
	 * @param titles The titles to use.
	 * @param ignoreFilter Set true to ignore the {@code blacklist}
	 * @param useTrackingCat Set true to append the MTC! tracking category to generated text
	 * @param cats Additional categories to add to generated text.
	 * @return A List of FileInfo objects for eligible files.
	 */
	public ArrayList<FileInfo> makeTransferFile(ArrayList<String> titles, boolean ignoreFilter, boolean useTrackingCat,
			ArrayList<String> cats)
	{
		HashMap<String, ArrayList<String>> catL = MQuery.getCategoriesOnPage(enwp, titles);
		if (!ignoreFilter)
			catL.forEach((k, v) -> {
				if (v.stream().anyMatch(blacklist::contains) || !v.stream().anyMatch(whitelist::contains))
					titles.remove(k);
			});

		MQuery.getSharedDuplicatesOf(enwp, titles).forEach((k, v) -> {
			if (!v.isEmpty())
				titles.remove(k);
		});

		ArrayList<FileInfo> l = new ArrayList<>();
		MQuery.exists(com, titles).forEach((k, v) -> {

			// determine own work status
			boolean isOwnWork = catL.get(k).contains("Category:Self-published work");

			// resolve file name conflicts between transfers.
			String comFN;
			if (!v)
				comFN = k;
			else
				do
				{
					comFN = new StringBuilder(k).insert(k.lastIndexOf('.'), " " + Math.round(Math.random() * 1000)).toString();
				} while (com.exists(comFN));

			l.add(new FileInfo(k, comFN, isOwnWork, useTrackingCat, cats));
		});

		return l;
	}

	/**
	 * Creates and stores generated wikitext for files to transfer.
	 * 
	 * @author Fastily
	 *
	 */
	public class FileInfo
	{
		/**
		 * The enwp filename
		 */
		public String wpFN;

		/**
		 * The commons filename
		 */
		public String comFN;

		/**
		 * The output text for Commons.
		 */
		public String comText;

		/**
		 * Flag indicating if the file is own work
		 */
		private boolean isOwnWork;

		/**
		 * Flag indicating if the file should use the MTC! tracking category.
		 */
		private boolean useTrackingCat;

		/**
		 * Categories to add to the output text.
		 */
		private ArrayList<String> cats;

		/**
		 * Constructor, creates a TransferObject
		 * 
		 * @param wpFN The enwp title to transfer
		 * @param comFN The commons title to transfer to
		 * @param isOwnWork Set true if the file is tagged as own work.
		 * @param useTrackingCat Set true to append the MTC! tracking category to generated text.
		 * @param cats Additional categories to add to generated text.
		 */
		protected FileInfo(String wpFN, String comFN, boolean isOwnWork, boolean useTrackingCat, ArrayList<String> cats)
		{
			this.comFN = comFN;
			this.wpFN = wpFN;
			this.isOwnWork = isOwnWork;

			this.cats = cats;
		}

		/**
		 * Actually generates the wikitext.
		 */
		public void gen()
		{
			StringBuilder sumSection = new StringBuilder("== {{int:filedesc}} ==\n"),
					licSection = new StringBuilder("\n== {{int:license-header}} ==\n");

			ArrayList<ImageInfo> imgInfoL = enwp.getImageInfo(wpFN);
			String uploader = imgInfoL.get(imgInfoL.size() - 1).user;

			// preprocess text
			String txt = enwp.getPageText(wpFN);
			txt = txt.replaceAll(mtcRegex, ""); // strip copy to commons

			txt = txt.replaceAll("(?s)\\<!\\-\\-.*?\\-\\-\\>", ""); // strip comments
			txt = txt.replaceAll("(?i)\\n?\\[\\[(Category:).*?\\]\\]", ""); // categories don't transfer well.
			txt = txt.replaceAll("\\n?\\=\\=.*?\\=\\=\\n?", ""); // strip headers
			txt = txt.replaceAll("(?si)\\{\\|\\s*?class\\=\"wikitable.+?\\|\\}", ""); // strip captions
			txt = txt.replaceAll("(?si)\\{\\{(bots|nobots).*?\\}\\}", ""); // strip nobots

			WikiText docRoot = WParser.parseText(enwp, txt);
			ArrayList<WTemplate> masterTPL = docRoot.getTemplatesR();

			// Normalize template titles
			masterTPL.forEach(t -> {
				t.normalizeTitle(enwp);

				if (tpMap.containsKey(t.title))
					t.title = tpMap.get(t.title);
			});

			// Filter Templates which are not on Commons
			MQuery.exists(com, FL.toAL(
					masterTPL.stream().filter(t -> !ctpCache.containsKey(t.title)).map(t -> com.convertIfNotInNS(t.title, NS.TEMPLATE))))
					.forEach((k, v) -> ctpCache.put(com.nss(k), v));
			masterTPL.removeIf(t -> {
				if (ctpCache.containsKey(t.title) && !ctpCache.get(t.title))
				{
					t.drop();
					return true;
				}
				return false;
			});

			// Transform special Templates
			WTemplate info = null;
			for (WTemplate t : masterTPL)
				switch (t.title)
				{
					case "Information":
						info = t;
						break;
					case "Self":
						if (!t.has("author"))
							t.put("author", String.format("{{User at project|%s|w|en}}", uploader));
						break;
					case "PD-self":
						t.title = "PD-user-en";
						t.put("1", uploader);
						break;
					case "GFDL-self-with-disclaimers":
						t.title = "GFDL-user-en-with-disclaimers";
						t.put("1", uploader);
						break;
					case "GFDL-self":
						t.title = "GFDL-self-en";
						t.put("author", String.format("{{User at project|%s|w|en}}", uploader));
						break;
					default:
				}

			if (info != null)
			{
				masterTPL.remove(info);
				info.drop();
			}

			// Add any Commons-compatible top-level templates to License section.
			masterTPL.retainAll(docRoot.getTemplates());
			masterTPL.forEach(t -> {
				licSection.append(String.format("%s%n", t));
				t.drop();
			});

			// fill-out an Information Template
			sumSection.append(String.format(
					"{{Information\n|description=%s\n|source=%s\n|date=%s\n|author=%s\n|permission=%s\n|other_versions=%s\n}}\n",
					fuzzForParam(info, "Description", "") + docRoot.toString().trim(),
					fuzzForParam(info, "Source", isOwnWork ? "{{Own work by original uploader}}" : "").trim(),
					fuzzForParam(info, "Date", "").trim(),
					fuzzForParam(info, "Author", isOwnWork ? String.format("[[User:%s|%s]]", uploader, uploader) : "").trim(),
					fuzzForParam(info, "Permission", "").trim(), fuzzForParam(info, "Other_versions", "").trim()));

			// Work with text as String
			comText = sumSection.toString() + licSection.toString();
			comText = comText.replaceAll("(?<=\\[\\[)(.+?\\]\\])", "w:$1"); // add enwp prefix to links
			comText = comText.replaceAll("(?i)\\[\\[(w::|w:w:)", "[[w:"); // Remove any double colons in interwiki links
			comText = comText.replaceAll("\\n{3,}", "\n"); // Remove excessive spacing

			// Generate Upload Log Section
			comText += "\n== {{Original upload log}} ==\n" + String.format("{{Original file page|en.wikipedia|%s}}%n", enwp.nss(wpFN))
					+ "{| class=\"wikitable\"\n! {{int:filehist-datetime}} !! {{int:filehist-dimensions}} !! {{int:filehist-user}} "
					+ "!! {{int:filehist-comment}}";

			for (ImageInfo ii : imgInfoL)
				comText += String.format("%n|-%n| %s || %d × %d || [[w:User:%s|%s]] || ''<nowiki>%s</nowiki>''",
						FSystem.iso8601dtf.format(LocalDateTime.ofInstant(ii.timestamp, ZoneOffset.UTC)), ii.width, ii.height, ii.user,
						ii.user, ii.summary.replace("\n", " ").replace("  ", " "));
			comText += "\n|}\n";

			// Fill in cats
			if (cats.isEmpty())
				comText += "\n{{Subst:Unc}}";
			else
				for (String s : cats)
					comText += String.format("\n[[%s]]", com.convertIfNotInNS(s, NS.CATEGORY));

			if (useTrackingCat)
				comText += "\n[[Category:Uploaded with MTC!]]";
		}

		/**
		 * Fuzz for a parameter in an Information template.
		 * 
		 * @param t The Information Template as a WTemplate
		 * @param k The key to look for. Use a capitalized form first.
		 * @param defaultP The default String to return if {@code k} and its variations were not found in {@code t}
		 * @return The parameter, as a String, or {@code defaultP} if the parameter could not be found.
		 */
		private String fuzzForParam(WTemplate t, String k, String defaultP)
		{
			String fzdKey = k;
			return t != null && (t.has(fzdKey) || t.has(fzdKey = k.toLowerCase()) || t.has(fzdKey = fzdKey.replace('_', ' ')))
					? t.get(fzdKey).toString()
					: defaultP;
		}
	}

	/**
	 * Simple controller for MTC-web.
	 * 
	 * @author Fastily
	 *
	 */
	@RestController
	public static class MTCWebController
	{
		/**
		 * The MTC object to use
		 */
		private MTC mtc = new MTC();

		/**
		 * Generates description pages for the specified titles.
		 * 
		 * @param title The titles to get description pages for.  Multiple parameters are acceptable.
		 * @param ignoreFilter Set true to ignore the {@code blacklist}
		 * @param useTrackingCat Set true to append the MTC! tracking category to generated text
		 * @param cats Additional categories to add to generated text.
		 * @return A List of FileInfo objects created from eligible files.
		 */
		@RequestMapping(value = "/genDesc", method = { RequestMethod.GET, RequestMethod.POST })
		public ArrayList<FileInfo> generateDescPage(@RequestParam(defaultValue = "") String[] title,
				@RequestParam(defaultValue = "false") boolean ignoreFilter, @RequestParam(defaultValue = "false") boolean useTrackingCat,
				@RequestParam(defaultValue = "") String[] cats)
		{
			ArrayList<FileInfo> fl = mtc.makeTransferFile(FL.toSAL(title), ignoreFilter, useTrackingCat, FL.toSAL(cats));
			fl.forEach(FileInfo::gen);

			return fl;
		}
	}
}