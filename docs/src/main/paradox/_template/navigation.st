<div class="nav-home">
<a href="$page.home.href$" $if(page.home.active)$class="active"$endif$>
<img src="$page.base$images/cloudstate-horizontal-color.svg" alt="$page.home.title$"/>
</a>
<!--
<div class="version-number">
$page.properties.("project.version.short")$
</div>
-->
</div>
$groups()$
<div class="nav-toc">
$page.navigation$
</div>