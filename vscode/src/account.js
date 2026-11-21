const { load } = require('cheerio/slim');

const avatarHosts = new Set(['training.olinfo.it', 'www.gravatar.com', 'secure.gravatar.com',
  'gravatar.com', 'avatars.githubusercontent.com', 'lh3.googleusercontent.com']);
const origin = 'https://training.olinfo.it';
const usernamePattern = /^[a-z0-9_.]{3,39}$/i;
const colors = ['red', 'orange', 'yellow', 'lime', 'green'];

function scoreColor(score) {
  return score === undefined || score === null ? 'neutral' : colors[Math.min(4, Math.floor(Math.max(0, score) / 25))];
}

function accountUsername(html) {
  const $ = load(html);
  const links = $('.navbar a[href^="/user/"], [class*="_navbar"] a[href^="/user/"], nav a[href^="/user/"]');
  for (const link of links) {
    const candidate = $(link).attr('href').slice('/user/'.length);
    if (usernamePattern.test(candidate) && $(link).text().trim() === 'Profilo') return candidate;
  }
}

function profile(html) {
  const $ = load(html);
  const values = new Map();
  for (const element of $('.stat')) {
    const label = $(element).children('.stat-title').text().trim();
    const value = $(element).children('.stat-value').text().trim();
    if (/^\d{1,9}$/.test(value)) values.set(label, Number(value));
  }
  if (!values.has('Punti') || !values.has('Problemi risolti')) throw new Error('Statistiche profilo Olinfo non disponibili.');
  let avatar;
  try {
    const url = new URL($('img[alt^="Foto profilo di "]').first().attr('src'), origin);
    if (url.protocol === 'https:' && !url.username && !url.password && !url.port && avatarHosts.has(url.hostname)) {
      avatar = url.href;
    }
  } catch { /* The account uses its initials when a photo is unavailable. */ }
  return { points: values.get('Punti'), solved: values.get('Problemi risolti'), avatar };
}

function rankingPage(html) {
  const $ = load(html);
  const rows = [];
  for (const link of $('li > a[href^="/user/"]')) {
    const user = $(link).attr('href').slice('/user/'.length);
    const rank = $(link).children().first().children().first().text().trim();
    const points = $(link).children().last().text().trim();
    if (usernamePattern.test(user) && /^\d{1,9}$/.test(rank) && /^\d{1,9}$/.test(points)) {
      rows.push({ user, rank: Number(rank), points: Number(points) });
    }
  }
  let last = 1;
  for (const link of $('a[href^="/ranking/"]')) {
    const page = /^\/ranking\/(\d{1,7})(?:\?|$)/.exec($(link).attr('href'));
    if (page) last = Math.max(last, Number(page[1]));
  }
  return { rows, last };
}

async function rankFor(page, username, points) {
  if (!points) return undefined;
  const first = rankingPage(await page('/ranking/1'));
  if (!first.rows.length) return undefined;
  const read = async n => n === 1 ? first.rows : rankingPage(await page('/ranking/' + n)).rows;
  const sameUser = row => row.user.toLowerCase() === username.toLowerCase();
  let left = 1;
  let right = first.last;
  while (left < right) {
    const mid = Math.floor((left + right) / 2);
    const rows = await read(mid);
    const hit = rows.find(sameUser);
    if (hit) return hit.rank;
    if (rows.at(-1)?.points > points) left = mid + 1;
    else right = mid;
  }
  // Start at the first page that could contain this score and handle ties across page boundaries.
  for (let pageNumber = left; pageNumber <= Math.min(first.last, left + 31); pageNumber++) {
    const rows = await read(pageNumber);
    const hit = rows.find(sameUser);
    if (hit) return hit.rank;
    if (!rows.length || rows.at(-1).points < points) break;
  }
}

module.exports = { accountUsername, profile, rankFor, scoreColor };
